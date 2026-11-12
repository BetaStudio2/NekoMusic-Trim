package com.neko.music.service;

import com.neko.music.Main;
import com.neko.music.util.MusicPinyinColumns;
import org.slf4j.Logger;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.nio.file.Path;
import java.nio.file.Files;

/**
 * 音乐入库（管理员上传/更新、JSON 添加/编辑、审核通过、补全入库）共用的低层操作。
 *
 * <p>仅收敛各流程中「序列、参数、消息、失败回滚语义」完全一致的片段：
 * 声纹索引失效、users 存在性校验、music 行插入、失败回滚删除、歌词 upsert + 检索索引重建。
 * 各调用处保留各自原有的 logger、文案与异常处理，输出与行为不变。
 */
public final class MusicIngestSupport {

    private MusicIngestSupport() {
    }

    /** 声纹索引失效（成功入库后触发）。 */
    public static void invalidateRecognitionIndex() {
        if (Main.getMusicRecognitionService() != null) {
            Main.getMusicRecognitionService().invalidateIndex();
        }
    }

    /** 判断 users 表中是否存在该用户。 */
    public static boolean isUserExists(Connection conn, int userId) throws SQLException {
        try (PreparedStatement stmt = conn.prepareStatement("SELECT COUNT(*) FROM users WHERE id = ?")) {
            stmt.setInt(1, userId);
            try (ResultSet rs = stmt.executeQuery()) {
                return rs.next() && rs.getInt(1) > 0;
            }
        }
    }

    /**
     * 插入 music 行并返回新 id。列/参数顺序与各调用处完全一致。
     *
     * <p>{@code albumColumn} 为写入 album 列的值，{@code albumForPinyin} 为写入拼音列所用的值
     * （各调用处对 null/空专辑的归一化不同，必须分开传入才能保持行为一致）；
     * {@code language}/{@code tags} 亦由调用方按各自语义预归一化后传入。
     * {@code affectedZeroMessage}/{@code missingKeyMessage} 保留各调用处原有异常文案。
     */
    public static int insertMusicRow(
            Connection conn,
            String title, String artist,
            String albumColumn, String albumForPinyin,
            String language, String tags,
            int duration, Integer uploadUserId, String fileFormat,
            String affectedZeroMessage, String missingKeyMessage) throws SQLException {
        String sql = "INSERT INTO music (title, artist, album, language, tags, duration, file_format, upload_user_id, title_pinyin, title_pinyin_initials, title_word_initials, artist_pinyin, artist_pinyin_initials, artist_word_initials, album_pinyin) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";
        try (PreparedStatement stmt = conn.prepareStatement(sql, PreparedStatement.RETURN_GENERATED_KEYS)) {
            stmt.setString(1, title);
            stmt.setString(2, artist);
            stmt.setString(3, albumColumn);
            stmt.setString(4, language);
            stmt.setString(5, tags);
            stmt.setInt(6, duration);
            stmt.setString(7, fileFormat);
            stmt.setObject(8, uploadUserId);
            // 预计算拼音列
            MusicPinyinColumns.bind(stmt, 9, title, artist, albumForPinyin);

            int affectedRows = stmt.executeUpdate();
            if (affectedRows == 0) {
                throw new SQLException(affectedZeroMessage);
            }
            try (ResultSet generatedKeys = stmt.getGeneratedKeys()) {
                if (generatedKeys.next()) {
                    return generatedKeys.getInt(1);
                }
                throw new SQLException(missingKeyMessage);
            }
        }
    }

    /** 文件正式落盘后写入统一的原始音质信息。失败时保留入库结果，交由重算脚本补齐。 */
    public static void refreshQualityAfterIngest(int musicId, Path audioPath, Logger logger) {
        try {
            if (Files.isRegularFile(audioPath)) {
                MusicQualityService.refreshDatabase(musicId, audioPath);
            }
        } catch (Exception e) {
            logger.warn("计算音乐原始音质失败 id={}，可用历史重算脚本补齐", musicId, e);
        }
    }

    /** 尽力删除 music 行（入库失败回滚），失败仅按调用方文案打日志。 */
    public static void deleteMusicRecordById(int musicId, Logger logger, String failureMessage) {
        try (Connection conn = Main.getDatabaseManager().getConnection();
             PreparedStatement stmt = conn.prepareStatement("DELETE FROM music WHERE id = ?")) {
            stmt.setInt(1, musicId);
            stmt.executeUpdate();
        } catch (SQLException e) {
            logger.error(failureMessage, musicId, e);
        }
    }

    /**
     * 歌词 upsert 成功则重建检索索引；日志走调用方 logger，与各调用处原始文案一致。
     *
     * <p>写入失败时抛出 {@link IllegalStateException}，由调用方决定回滚或返回错误，
     * 避免「接口返回成功但歌词没入库」的静默失败。
     */
    public static void saveLyricsAndRebuild(int musicId, String lyricsContent, Logger logger) {
        saveLyricsAndRebuild(musicId, lyricsContent, "lrc", logger);
    }

    /** 指定歌词存储格式写入；{@code neko-v1} 会保留逐字时间轴。 */
    public static void saveLyricsAndRebuild(int musicId, String lyricsContent, String format, Logger logger) {
        if (!Main.getLyricsDatabaseManager().upsert(musicId, lyricsContent, format)) {
            logger.error("保存数据库歌词失败 musicId={}", musicId);
            throw new IllegalStateException("保存歌词到数据库失败 musicId=" + musicId);
        }
        logger.info("歌词已保存到数据库 musicId={}", musicId);
        if (Main.getLyricsSearchIndex() != null) {
            Main.getLyricsSearchIndex().rebuildOne(musicId);
        }
    }
}
