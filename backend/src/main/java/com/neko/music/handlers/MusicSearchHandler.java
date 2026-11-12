package com.neko.music.handlers;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.neko.music.Main;
import com.neko.music.service.AdminMusicIngestService;
import com.neko.music.service.ExternalFillQuotaService;
import com.neko.music.service.MusicSearchRanker;
import com.neko.music.service.NeteaseSearchFillService;
import com.neko.music.service.SearchIndexService;
import com.neko.music.search.SearchSession;
import com.neko.music.util.ChineseConverter;
import com.neko.music.util.ClientIpResolver;
import com.neko.music.util.RequestAuthUtil;
import org.eclipse.jetty.http.HttpStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import com.neko.music.i18n.ApiMessages;

public class MusicSearchHandler extends ApiServlet {
    private static final Logger logger = LoggerFactory.getLogger(MusicSearchHandler.class);
    private static final int SEARCH_LIMIT = 50;
    /** 单个查询词条的候选上限；多词条查询按词条分别取回后在内存里合并去重。 */
    private static final int TERM_FETCH_LIMIT = SEARCH_LIMIT * 3;
    private static final int STRONG_METADATA_SCORE = 80;
    /** 索引召回的单次取回上限；与旧歌词索引的合并上限保持一致。 */
    private static final int MAX_INDEX_HITS = 80;
    private static final Set<String> LOW_INFORMATION_ENGLISH_WORDS = Set.of(
            "a", "an", "and", "are", "be", "do", "for", "i", "in", "is", "it", "its",
            "la", "me", "my", "na", "of", "oh", "on", "or", "the", "to", "we",
            "with", "ya", "yeah", "you", "your"
    );

    private static final String MUSIC_BASE_COLUMNS =
            "id, title, artist, album, duration, upload_user_id, created_at, max_quality";
    private static final String MUSIC_SEARCH_COLUMNS = MUSIC_BASE_COLUMNS + ", " +
            "title_pinyin, title_pinyin_initials, title_word_initials, " +
            "artist_pinyin, artist_pinyin_initials, artist_word_initials, album_pinyin ";
    private static final String TERM_TEXT_CONDITION = "(title LIKE ? OR artist LIKE ? OR album LIKE ?)";
    private static final String TERM_PINYIN_CONDITION =
            "(title_pinyin LIKE ? OR title_pinyin_initials LIKE ? OR title_word_initials LIKE ? "
            + "OR artist_pinyin LIKE ? OR artist_pinyin_initials LIKE ? OR artist_word_initials LIKE ? "
            + "OR album_pinyin LIKE ?)";
    private static final int TERM_PINYIN_PARAM_COUNT = 7;

    @Override
    protected void doPost(HttpServletRequest request, HttpServletResponse response) throws IOException {
        String requestBody = new String(request.getInputStream().readAllBytes(), "UTF-8");

        try {
            SearchRequest searchRequest = Main.getObjectMapper().readValue(requestBody, SearchRequest.class);
            boolean hasQuery = searchRequest.query() != null && !searchRequest.query().isBlank();
            boolean hasItems = searchRequest.items() != null && !searchRequest.items().isEmpty();

            if (hasQuery && hasItems) {
                sendErrorObject(response, HttpStatus.BAD_REQUEST_400, "search.query_and_items_conflict");
                return;
            }
            if (!hasQuery && !hasItems) {
                sendErrorObject(response, HttpStatus.BAD_REQUEST_400, "search.query_or_items_required");
                return;
            }

            if (hasItems) {
                handleBatchSearch(searchRequest.items(), request, response);
                return;
            }

            handleLegacySearch(searchRequest.query().trim(), request, response);

        } catch (Exception e) {
            // Jackson 解析异常的 message 含全限定处理器/DTO 类名与字段清单，只能落日志，响应只回泛化提示
            logger.warn("搜索请求处理失败", e);
            sendErrorObject(response, HttpStatus.BAD_REQUEST_400, "auth.bad_request");
        }
    }


    private void handleLegacySearch(String query, HttpServletRequest request, HttpServletResponse response)
            throws IOException {
        List<Music> results = searchMusic(query);

        String message = "playlist.search_ok";
        // 站内元数据 + 歌词均无结果时，先走外站补全（网易云优先，无结果再 QQ 兜底），再决定是否返回空结果。
        // 外站补全消耗来源配额，超出只跳过补全（仍返回站内结果），不拒绝整个搜索请求。
        if (results.isEmpty() && externalFillEnabled() && allowExternalFill(request)) {
            NeteaseSearchFillService.FillAttempt fill =
                    Main.getNeteaseSearchFillService().tryFillFromNetease(query);
            if (fill.music().isPresent()) {
                results.add(toSearchMusic(fill.music().get()));
                message = "playlist.search_ok";
            } else {
                message = neteaseFillFailureMessage(message, fill.reason());
            }
        }

        Object responseResults = results.isEmpty() ? null : results;
        if (results.isEmpty() && "playlist.search_ok".equals(message)) {
            message = "search.not_found";
        }
        if (!results.isEmpty()) {
            recordAuthenticatedSearch(request, query);
        }
        writeSearchResponse(response, !results.isEmpty(), message, responseResults);
    }

    /**
     * 只记录已登录用户且确有站内结果的搜索词。记录异步执行，队列满时放弃画像信号，
     * 不在公开搜索路径上等待数据库。
     */
    private static void recordAuthenticatedSearch(HttpServletRequest request, String query) {
        String token = RequestAuthUtil.extractToken(request);
        var authService = Main.getUserAuthService();
        Integer userId = token == null || authService == null
                ? null
                : authService.validateToken(token).orElse(null);
        if (userId == null) {
            return;
        }
        var historyService = Main.getUserSearchHistoryService();
        if (historyService != null) {
            historyService.recordAsync(userId, query);
        }
    }

    private void handleBatchSearch(List<SearchItem> items, HttpServletRequest request, HttpServletResponse response)
            throws IOException {
        List<Music> results = new ArrayList<>(items.size());
        List<PendingNeteaseFill> pendingFills = new ArrayList<>();
        int foundCount = 0;
        int fillCount = 0;
        String lastFillFailure = null;

        for (SearchItem item : items) {
            if (item == null || item.title() == null || item.title().isBlank()) {
                sendErrorObject(response, HttpStatus.BAD_REQUEST_400, "search.items_title_required");
                return;
            }
            String title = item.title().trim();
            String artist = item.artist() == null ? "" : item.artist().trim();

            Music music = null;
            try {
                music = findBestLocalMusicForBatchItem(title, artist);
            } catch (SQLException e) {
                logger.error("批量搜索查库失败 title={} artist={}", title, artist, e);
            }

            if (music == null && externalFillEnabled()) {
                pendingFills.add(new PendingNeteaseFill(results.size(), title, artist));
            }

            results.add(music);
            if (music != null) {
                foundCount++;
            }
        }

        if (!pendingFills.isEmpty()) {
            // 外站补全逐条消耗来源配额：超出的条目只跳过补全（仍保留站内结果），不拒绝整个请求。
            List<PendingNeteaseFill> allowedFills = new ArrayList<>(pendingFills.size());
            for (PendingNeteaseFill pending : pendingFills) {
                if (allowExternalFill(request)) {
                    allowedFills.add(pending);
                }
            }
            if (allowedFills.size() < pendingFills.size()) {
                logger.warn("批量搜索外站补全超过来源配额，跳过 {} 条",
                        pendingFills.size() - allowedFills.size());
            }
            if (!allowedFills.isEmpty()) {
                List<NeteaseSearchFillService.FillRequest> fillRequests = allowedFills.stream()
                        .map(p -> new NeteaseSearchFillService.FillRequest(p.title(), p.artist()))
                        .toList();
                List<NeteaseSearchFillService.FillAttempt> fillAttempts =
                        Main.getNeteaseSearchFillService().tryFillBatchFromNetease(fillRequests);
                for (int i = 0; i < allowedFills.size(); i++) {
                    PendingNeteaseFill pending = allowedFills.get(i);
                    NeteaseSearchFillService.FillAttempt fill = i < fillAttempts.size()
                            ? fillAttempts.get(i)
                            : FillAttemptErrorHolder.ERROR;
                    if (fill.music().isPresent()) {
                        results.set(pending.resultIndex(), toSearchMusic(fill.music().get()));
                        foundCount++;
                        fillCount++;
                    } else if (fill.reason() != NeteaseSearchFillService.FillReason.NONE) {
                        lastFillFailure = neteaseFillFailureMessage("playlist.search_ok", fill.reason());
                    }
                }
            }
        }

        String message = buildBatchMessage(foundCount, items.size(), fillCount, lastFillFailure);
        writeSearchResponse(response, foundCount > 0, message, results);
    }

    private record PendingNeteaseFill(int resultIndex, String title, String artist) {}

    private static final class FillAttemptErrorHolder {
        private static final NeteaseSearchFillService.FillAttempt ERROR =
                new NeteaseSearchFillService.FillAttempt(
                        java.util.Optional.empty(),
                        NeteaseSearchFillService.FillReason.ERROR);
    }

    private static String buildBatchMessage(int found, int total, int fillCount, String lastFillFailure) {
        if (found == 0) {
            return lastFillFailure != null ? lastFillFailure : "search.not_found";
        }
        if (fillCount > 0) {
            return ApiMessages.translate("search.ok_partial_filled", found, total, fillCount);
        }
        return found == total ? ApiMessages.translate("playlist.search_ok") : ApiMessages.translate("search.ok_partial", found, total);
    }

    /**
     * 外站补全来源配额闸门：公开搜索未命中站内曲库时会触发服务端外站下载入库，这里按来源限制触发次数，
     * 避免被构造大量不同歌名刷带宽 / 磁盘。超配额只跳过补全，不影响正常返回站内结果。
     */
    private static boolean allowExternalFill(HttpServletRequest request) {
        ExternalFillQuotaService quota = Main.getExternalFillQuotaService();
        if (quota == null) {
            // 未初始化（例如脱离 Main 的单元测试）时不限流，避免把搜索链路打死
            return true;
        }
        return quota.tryAcquire(ClientIpResolver.clientIp(request));
    }

    /** 外站补全总开关：网易云或 QQ 任一开启即视为启用（网易云优先，无结果再 QQ 兜底）。 */
    private static boolean externalFillEnabled() {
        return Main.getConfigManager().isNeteaseSearchFillEnabled()
                || Main.getConfigManager().isQqSearchFillEnabled();
    }

    private static String neteaseFillFailureMessage(String defaultMessage, NeteaseSearchFillService.FillReason reason) {
        return switch (reason) {
            case LOGIN_EXPIRED ->
                    "search.not_found_login_expired";
            case ERROR -> "search.not_found";
            case LOW_DISK_SPACE -> com.neko.music.util.RuntimeDiskGuard.neteaseFillBlockedMessage();
            case NOT_FOUND -> "search.not_found";
            case NON_MUSIC -> "search.not_found";
            default -> defaultMessage;
        };
    }

    private void writeSearchResponse(
            HttpServletResponse response,
            boolean success,
            String message,
            Object results
    ) throws IOException {
        SearchResponse searchResponse = new SearchResponse(success, ApiMessages.translate(message), results);
        response.setStatus(HttpStatus.OK_200);
        response.setContentType("application/json;charset=utf-8");
        response.getWriter().println(Main.getObjectMapper().writeValueAsString(searchResponse));
    }

    private Music findBestLocalMusicForBatchItem(String title, String artist) throws SQLException {
        return Main.getAdminMusicIngestService()
                .findBestLocalMatchForBatchItem(title, artist)
                .map(MusicSearchHandler::toSearchMusic)
                .orElse(null);
    }

    /**
     * 使用预计算拼音列进行SQL筛选，避免全表加载到内存。
     *
     * <p>查询串先按「歌名 + 歌手」拆成词条（如 晴天 周杰伦、周杰伦-晴天），
     * 每个词条各自取回一批候选后再在内存里合并去重：这样即使歌手名下曲目极多，
     * 歌名词条的候选也不会被 created_at 排序窗口挤掉。
     */
    private List<Music> searchMusic(String query) {
        List<String> terms = MusicSearchRanker.splitTerms(query);
        if (terms.isEmpty()) {
            return new ArrayList<>();
        }

        List<Music> results = new ArrayList<>();

        try (Connection conn = Main.getDatabaseManager().getConnection()) {
            // 纯数字查询先按歌曲 ID 精确命中：用户从详情页地址（/detail/148）或客户端里抄来的编号，
            // 直接给这一首，不再去拼标题 / 歌手 / 歌词的模糊匹配——也就不会因为「148」这串数字
            // 恰好出现在别的曲目里，把用户要的那首挤到后面去。
            Optional<Integer> musicId = parseMusicId(query);
            if (musicId.isPresent()) {
                Music byId = findMusicById(conn, musicId.get());
                if (byId != null) {
                    // 不查歌词索引：lrc 的语义是「本次由歌词正文命中」（见 Music#lrc），按 ID 命中
                    // 与歌词无关，一律保持 false——否则客户端拿这个徽标当「含歌词」用就会时真时假。
                    results.add(byId);
                    logger.debug("搜索按歌曲 ID 命中: query='{}', id={}", query, musicId.get());
                    return results;
                }
            }

            // SQL已经筛选了候选集，只需在内存中做精细打分排序
            List<ScoredMusic> scoredResults = new ArrayList<>();
            int topMetadataScore = collectScoredCandidates(conn, terms, null, query, scoredResults);

            // 站内没有任何元数据命中时，再尝试「歌名 + 歌手」直接连写的拆分（如 晴天周杰伦）
            if (scoredResults.isEmpty()) {
                List<List<String>> concatenations = MusicSearchRanker.splitConcatenations(query);
                if (!concatenations.isEmpty()) {
                    topMetadataScore = collectScoredCandidates(conn, concatenationParts(concatenations),
                            concatenations, query, scoredResults);
                }
            }

            // 歌词正文 / 拼音 / 首字母命中走本地索引库；短查询且元数据已有结果时不必再跑一遍索引。
            if (shouldSearchLyricsForQuery(query, topMetadataScore, !scoredResults.isEmpty())
                    || scoredResults.isEmpty()) {
                // 索引召回 / 回表失败只降级：已经拿到的元数据结果必须照常返回。
                try {
                    mergeIndexSearchResults(conn, query, scoredResults);
                } catch (Exception e) {
                    logger.warn("索引召回失败，继续使用元数据结果: query='{}', error={}",
                            query, e.toString());
                }
            }

            // 按分数排序
            scoredResults.sort((a, b) -> Integer.compare(b.score, a.score));

            for (int i = 0; i < Math.min(SEARCH_LIMIT, scoredResults.size()); i++) {
                results.add(scoredResults.get(i).music);
            }

            logger.debug("搜索完成: query='{}', 词条={}, 结果数={}", query, terms, results.size());

        } catch (Exception e) {
            logger.error("搜索音乐时出错", e);
        }

        return results;
    }

    /**
     * 取回候选并在内存中打分，结果追加到 {@code collected}。
     *
     * @param terms          用于 SQL 候选筛选的词条
     * @param concatenations 非空时按「歌名 + 歌手」连写拆分打分，否则按 {@code terms} 分角色打分
     * @return 本批候选里「完整查询串」的最高元数据分（用于决定是否还要搜歌词）
     */
    private int collectScoredCandidates(
            Connection conn, List<String> terms, List<List<String>> concatenations, String rawQuery,
            List<ScoredMusic> collected) throws SQLException {
        int topMetadataScore = 0;
        Set<Integer> seenIds = new HashSet<>();

        try (PreparedStatement stmt = conn.prepareStatement(buildCandidateSql(terms))) {
            int paramIndex = 1;
            for (String term : terms) {
                for (String variant : searchVariants(term)) {
                    String likeVariant = "%" + variant + "%";
                    for (int i = 0; i < 3; i++) {
                        stmt.setString(paramIndex++, likeVariant);
                    }
                }
                String likeTerm = "%" + term.toLowerCase(Locale.ROOT) + "%";
                if (needsPinyinConditions(term)) {
                    for (int i = 0; i < TERM_PINYIN_PARAM_COUNT; i++) {
                        stmt.setString(paramIndex++, likeTerm);
                    }
                }
                stmt.setInt(paramIndex++, TERM_FETCH_LIMIT);
            }

            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    int id = rs.getInt("id");
                    if (!seenIds.add(id)) {
                        continue;
                    }

                    Music music = mapMusicRow(rs);
                    applyPinyinColumns(music, rs);

                    MusicSearchRanker.MusicFields fields = toRankerFields(music);
                    int score = concatenations == null
                            ? MusicSearchRanker.score(fields, terms)
                            : MusicSearchRanker.concatenatedScore(fields, concatenations);
                    if (score > 0) {
                        collected.add(new ScoredMusic(music, score));
                        // 歌词兜底只看用户输入的完整串，与拆分出的词条无关
                        topMetadataScore = Math.max(topMetadataScore,
                                MusicSearchRanker.wholeQueryStrength(fields, rawQuery));
                    }
                }
            }
        }
        return topMetadataScore;
    }

    /** 连写拆分涉及的全部片段（用于 SQL 候选筛选，去重保序）。 */
    private static List<String> concatenationParts(List<List<String>> concatenations) {
        Set<String> parts = new LinkedHashSet<>();
        for (List<String> hypothesis : concatenations) {
            parts.addAll(hypothesis);
        }
        return List.copyOf(parts);
    }

    /** 每个词条一个子查询，各自按 {@link #TERM_FETCH_LIMIT} 取候选，再 UNION ALL 合并。 */
    private static String buildCandidateSql(List<String> terms) {
        StringBuilder sql = new StringBuilder();
        for (int i = 0; i < terms.size(); i++) {
            if (i > 0) {
                sql.append(" UNION ALL ");
            }
            List<String> conditions = new ArrayList<>();
            for (int variant = 0; variant < searchVariants(terms.get(i)).size(); variant++) {
                conditions.add(TERM_TEXT_CONDITION);
            }
            // 拼音列只可能是 ASCII（pinyin4j 生成）：纯中文 / 全角词条永远不可能命中它们，
            // 加上这 7 个 LIKE 分支只是让每一行多比较 7 次。混合词条（如「晴天zhou」）仍然保留。
            if (needsPinyinConditions(terms.get(i))) {
                conditions.add(TERM_PINYIN_CONDITION);
            }
            sql.append("(SELECT ").append(MUSIC_SEARCH_COLUMNS)
                    .append("FROM music WHERE (").append(String.join(" OR ", conditions))
                    .append(") ORDER BY created_at DESC LIMIT ?)");
        }
        return sql.toString();
    }

    /** 该词条是否需要拼上拼音条件：含 ASCII 字母或数字时才有意义。 */
    static boolean needsPinyinConditions(String term) {
        if (term == null || term.isEmpty()) {
            return false;
        }
        for (int i = 0; i < term.length(); i++) {
            char c = term.charAt(i);
            if (c < 0x80 && Character.isLetterOrDigit(c)) {
                return true;
            }
        }
        return false;
    }

    /** 词条的繁简体/拼音变体（大小写归一后去重）。 */
    private static List<String> searchVariants(String term) {
        return ChineseConverter.getFullSearchVariants(term.toLowerCase(Locale.ROOT));
    }

    private static MusicSearchRanker.MusicFields toRankerFields(Music music) {
        return new MusicSearchRanker.MusicFields(
                music.getTitle(), music.getArtist(), music.getAlbum(),
                music.getTitlePinyin(), music.getTitlePinyinInitials(), music.getTitleWordInitials(),
                music.getArtistPinyin(), music.getArtistPinyinInitials(), music.getArtistWordInitials(),
                music.getAlbumPinyin());
    }

    /**
     * 用本地搜索索引召回候选（灰度开关 {@code search_index.enabled}）。
     *
     * <p>索引把歌名 / 歌手 / 专辑 / 歌词正文 / 拼音 / 首字母 / 读音统一建成词元，
     * 能补上 SQL LIKE 覆盖不到的拼音首字母、模糊音与歌词正文命中。
     * 命中后仍用既有排序器按元数据重新打分，与 SQL 候选保持同一尺度；
     * 元数据完全打不上分的命中视为歌词正文命中，按歌词档位给分并标记 lrc。</p>
     *
     * @return 本次新增的候选数；索引未启用 / 无命中时返回 0
     */
    private int mergeIndexSearchResults(Connection conn, String query, List<ScoredMusic> scoredResults)
            throws SQLException {
        SearchIndexService indexService = Main.getSearchIndexService();
        if (indexService == null || !indexService.enabled()) {
            return 0;
        }
        List<SearchSession.SearchHit> hits = indexService.search(query, MAX_INDEX_HITS, 1);
        if (hits.isEmpty()) {
            return 0;
        }

        Set<Integer> seenIds = new HashSet<>();
        List<Integer> ids = new ArrayList<>(hits.size());
        for (SearchSession.SearchHit hit : hits) {
            int id = parseTrackId(hit.trackId());
            if (id > 0 && seenIds.add(id)) {
                ids.add(id);
            }
        }
        if (ids.isEmpty()) {
            return 0;
        }

        Set<Integer> existingIds = new HashSet<>();
        for (ScoredMusic scored : scoredResults) {
            existingIds.add(scored.music.getId());
        }

        Map<Integer, Music> hitMusicById = fetchMusicByIdsWithPinyin(conn, ids);
        List<String> terms = MusicSearchRanker.splitTerms(query);
        int lyricsScore = calculateLyricsMatchScore(query);

        int added = 0;
        for (Integer id : ids) {
            if (existingIds.contains(id)) {
                continue;
            }
            Music music = hitMusicById.get(id);
            if (music == null) {
                continue;
            }
            int metadataScore = terms.isEmpty() ? 0 : MusicSearchRanker.score(toRankerFields(music), terms);
            if (metadataScore > 0) {
                scoredResults.add(new ScoredMusic(music, metadataScore));
            } else {
                music.setLrc(true);
                scoredResults.add(new ScoredMusic(music, lyricsScore));
            }
            added++;
        }
        return added;
    }

    /** 索引里的曲目标识就是 music.id 的字符串形式。 */
    private static int parseTrackId(String trackId) {
        if (trackId == null || trackId.isEmpty()) {
            return -1;
        }
        try {
            return Integer.parseInt(trackId);
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    /** 按 ID 批量取回曲目（含预计算拼音列），供索引召回后重新打分。 */
    private Map<Integer, Music> fetchMusicByIdsWithPinyin(Connection conn, List<Integer> ids) throws SQLException {
        if (ids == null || ids.isEmpty()) {
            return Map.of();
        }

        StringBuilder placeholders = new StringBuilder();
        for (int i = 0; i < ids.size(); i++) {
            if (i > 0) {
                placeholders.append(',');
            }
            placeholders.append('?');
        }

        String sql = "SELECT " + MUSIC_SEARCH_COLUMNS + " FROM music WHERE id IN (" + placeholders + ")";
        Map<Integer, Music> byId = new HashMap<>();
        try (PreparedStatement stmt = conn.prepareStatement(sql)) {
            for (int i = 0; i < ids.size(); i++) {
                stmt.setInt(i + 1, ids.get(i));
            }
            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    Music music = mapMusicRow(rs);
                    applyPinyinColumns(music, rs);
                    byId.put(music.getId(), music);
                }
            }
        }
        return byId;
    }

    static boolean shouldSearchLyricsForQuery(String query, int topMetadataScore, boolean hasMetadataResults) {
        if (query == null || query.isBlank()) {
            return false;
        }
        if (topMetadataScore >= STRONG_METADATA_SCORE) {
            return false;
        }

        QueryTextStats stats = analyzeQueryText(query);
        if (stats.letterOrDigitCount == 0 || stats.digitCount == stats.letterOrDigitCount) {
            return false;
        }

        if (stats.cjkCount > 0) {
            return stats.cjkCount > 6;
        }

        if (stats.latinWordCount > 0) {
            if (stats.latinWordCount <= 2) {
                return false;
            }
            int minLetters = stats.latinWordCount >= 4 ? 10 : 12;
            return stats.meaningfulLatinWordCount >= 2 && stats.latinLetterCount >= minLetters;
        }

        return stats.wordLikeTokenCount >= 2 && stats.letterOrDigitCount >= 8;
    }

    private static int calculateLyricsMatchScore(String query) {
        QueryTextStats stats = analyzeQueryText(query);
        if (stats.cjkCount >= 8 || stats.meaningfulLatinWordCount >= 4) {
            return 45;
        }
        if (stats.cjkCount >= 5 || stats.meaningfulLatinWordCount >= 3) {
            return 35;
        }
        return 25;
    }

    private static QueryTextStats analyzeQueryText(String query) {
        String normalized = com.neko.music.util.ChineseConverter.toSimplified(query)
                .toLowerCase(Locale.ROOT);
        int cjkCount = 0;
        int latinLetterCount = 0;
        int digitCount = 0;
        int letterOrDigitCount = 0;
        int wordLikeTokenCount = 0;
        int latinWordCount = 0;
        int meaningfulLatinWordCount = 0;
        Set<String> seenMeaningfulLatinWords = new HashSet<>();
        StringBuilder token = new StringBuilder();
        Character.UnicodeScript tokenScript = null;

        for (int i = 0; i < normalized.length(); ) {
            int cp = normalized.codePointAt(i);
            if (isCjkCodePoint(cp)) {
                cjkCount++;
            }
            if (Character.isDigit(cp)) {
                digitCount++;
            }
            if (Character.isLetterOrDigit(cp)) {
                letterOrDigitCount++;
            }

            Character.UnicodeScript script = Character.UnicodeScript.of(cp);
            if (Character.isLetter(cp)) {
                if (script == Character.UnicodeScript.LATIN) {
                    latinLetterCount++;
                }
                if (token.length() > 0 && tokenScript != script) {
                    TokenStats tokenStats = countToken(token.toString(), tokenScript);
                    wordLikeTokenCount += tokenStats.wordLikeTokenCount;
                    latinWordCount += tokenStats.latinWordCount;
                    meaningfulLatinWordCount += addMeaningfulLatinWord(
                            tokenStats.meaningfulLatinWord, seenMeaningfulLatinWords);
                    token.setLength(0);
                }
                token.appendCodePoint(cp);
                tokenScript = script;
            } else {
                if (token.length() > 0) {
                    TokenStats tokenStats = countToken(token.toString(), tokenScript);
                    wordLikeTokenCount += tokenStats.wordLikeTokenCount;
                    latinWordCount += tokenStats.latinWordCount;
                    meaningfulLatinWordCount += addMeaningfulLatinWord(
                            tokenStats.meaningfulLatinWord, seenMeaningfulLatinWords);
                    token.setLength(0);
                    tokenScript = null;
                }
            }
            i += Character.charCount(cp);
        }

        if (token.length() > 0) {
            TokenStats tokenStats = countToken(token.toString(), tokenScript);
            wordLikeTokenCount += tokenStats.wordLikeTokenCount;
            latinWordCount += tokenStats.latinWordCount;
            meaningfulLatinWordCount += addMeaningfulLatinWord(
                    tokenStats.meaningfulLatinWord, seenMeaningfulLatinWords);
        }

        return new QueryTextStats(cjkCount, latinLetterCount, digitCount, letterOrDigitCount,
                wordLikeTokenCount, latinWordCount, meaningfulLatinWordCount);
    }

    private static int addMeaningfulLatinWord(String word, Set<String> seen) {
        if (word == null || word.isBlank() || !seen.add(word)) {
            return 0;
        }
        return 1;
    }

    private static TokenStats countToken(String token, Character.UnicodeScript script) {
        if (token == null || token.isBlank()) {
            return new TokenStats(0, 0, null);
        }
        if (script == Character.UnicodeScript.LATIN) {
            String lower = token.toLowerCase(Locale.ROOT);
            boolean meaningful = lower.length() >= 3 && !LOW_INFORMATION_ENGLISH_WORDS.contains(lower);
            return new TokenStats(1, 1, meaningful ? lower : null);
        }
        return new TokenStats(1, 0, null);
    }

    private static boolean isCjkCodePoint(int cp) {
        Character.UnicodeScript script = Character.UnicodeScript.of(cp);
        return script == Character.UnicodeScript.HAN
                || script == Character.UnicodeScript.HIRAGANA
                || script == Character.UnicodeScript.KATAKANA
                || script == Character.UnicodeScript.HANGUL;
    }

    private static Music toSearchMusic(AdminMusicIngestService.IngestedMusic row) {
        Music music = new Music();
        music.setId(row.id());
        music.setTitle(row.title());
        music.setArtist(row.artist());
        music.setAlbum(row.album());
        music.setDuration(row.duration());
        music.setUploadUserId(row.uploadUserId());
        music.setCreatedAt(row.createdAt());
        music.setMaxQuality(row.maxQuality());
        return music;
    }

    /** 读取 music 行的公共字段（不含预计算拼音列）。 */
    /**
     * 查询串是不是「歌曲 ID」。
     *
     * <p>只在整串都是 ASCII 数字时才认，并且要求首位不为 0：带空格、连字符、字母，或者
     * {@code 007}、全角数字这类写法一律交回模糊搜索，否则「专辑叫 1989」「歌名是 24」这类
     * 正常搜索会被一个恰好存在的 ID 抢走。</p>
     *
     * @return 合法 ID；不是纯数字 / 越界 / 为 0 时返回 {@link Optional#empty()}
     */
    static Optional<Integer> parseMusicId(String query) {
        if (query == null) {
            return Optional.empty();
        }
        String trimmed = query.trim();
        if (trimmed.isEmpty() || trimmed.length() > 9 || trimmed.charAt(0) == '0') {
            return Optional.empty();
        }
        for (int i = 0; i < trimmed.length(); i++) {
            char c = trimmed.charAt(i);
            if (c < '0' || c > '9') {
                return Optional.empty();
            }
        }
        try {
            return Optional.of(Integer.parseInt(trimmed));
        } catch (NumberFormatException e) {
            return Optional.empty();
        }
    }

    /** 按 ID 取一首曲目；不存在返回 null。列与模糊搜索一致，保证返回结构（含 maxQuality）同形。 */
    private static Music findMusicById(Connection conn, int musicId) throws SQLException {
        String sql = "SELECT " + MUSIC_BASE_COLUMNS + " FROM music WHERE id = ?";
        try (PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setInt(1, musicId);
            try (ResultSet rs = stmt.executeQuery()) {
                return rs.next() ? mapMusicRow(rs) : null;
            }
        }
    }

    private static Music mapMusicRow(ResultSet rs) throws SQLException {
        Music music = new Music();
        music.setId(rs.getInt("id"));
        music.setTitle(rs.getString("title") != null ? rs.getString("title") : "");
        music.setArtist(rs.getString("artist") != null ? rs.getString("artist") : "");
        music.setAlbum(rs.getString("album") != null ? rs.getString("album") : "");
        music.setDuration(rs.getInt("duration"));
        music.setUploadUserId(rs.getInt("upload_user_id"));
        music.setCreatedAt(rs.getTimestamp("created_at") != null ? rs.getTimestamp("created_at").toString() : "");
        music.setMaxQuality(rs.getString("max_quality"));
        return music;
    }

    /** 读取查询侧需要用到的预计算拼音列（不返回给客户端）。 */
    private static void applyPinyinColumns(Music music, ResultSet rs) throws SQLException {
        music.setTitlePinyin(rs.getString("title_pinyin"));
        music.setTitlePinyinInitials(rs.getString("title_pinyin_initials"));
        music.setTitleWordInitials(rs.getString("title_word_initials"));
        music.setArtistPinyin(rs.getString("artist_pinyin"));
        music.setArtistPinyinInitials(rs.getString("artist_pinyin_initials"));
        music.setArtistWordInitials(rs.getString("artist_word_initials"));
        music.setAlbumPinyin(rs.getString("album_pinyin"));
    }

    // 辅助类：带分数的音乐
    private static class ScoredMusic {
        final Music music;
        int score;
        ScoredMusic(Music music, int score) {
            this.music = music;
            this.score = score;
        }
    }

    private record QueryTextStats(
            int cjkCount,
            int latinLetterCount,
            int digitCount,
            int letterOrDigitCount,
            int wordLikeTokenCount,
            int latinWordCount,
            int meaningfulLatinWordCount
    ) {
    }

    private record TokenStats(int wordLikeTokenCount, int latinWordCount, String meaningfulLatinWord) {
    }

    // 搜索请求（Jackson 反序列化，只读）
    private record SearchRequest(String query, List<SearchItem> items) {
    }

    private record SearchItem(String title, String artist) {
    }

    // 内部类：音乐对象（含拼音列）
    private static class Music {
        private int id;
        private String title;
        private String artist;
        private String album;
        private int duration;
        private int uploadUserId;
        private String createdAt;
        /** 该曲最高音质（standard/hq/sq/hires），与 /api/music/info 的 maxQuality 同义；未探测过为 null。 */
        private String maxQuality;
        // 预计算拼音列
        private String titlePinyin;
        private String titlePinyinInitials;
        private String titleWordInitials;
        private String artistPinyin;
        private String artistPinyinInitials;
        private String artistWordInitials;
        private String albumPinyin;
        /** 是否由本次歌词正文搜索命中。 */
        private boolean lrc = false;

        public int getId() { return id; }
        public void setId(int id) { this.id = id; }
        public String getTitle() { return title; }
        public void setTitle(String title) { this.title = title; }
        public String getArtist() { return artist; }
        public void setArtist(String artist) { this.artist = artist; }
        public String getAlbum() { return album; }
        public void setAlbum(String album) { this.album = album; }
        public int getDuration() { return duration; }
        public void setDuration(int duration) { this.duration = duration; }
        public int getUploadUserId() { return uploadUserId; }
        public void setUploadUserId(int uploadUserId) { this.uploadUserId = uploadUserId; }
        public String getCreatedAt() { return createdAt; }
        public void setCreatedAt(String createdAt) { this.createdAt = createdAt; }
        public String getMaxQuality() { return maxQuality; }
        public void setMaxQuality(String maxQuality) { this.maxQuality = maxQuality; }
        public boolean getLrc() { return lrc; }
        public void setLrc(boolean lrc) { this.lrc = lrc; }
        @JsonIgnore
        public String getTitlePinyin() { return titlePinyin; }
        public void setTitlePinyin(String titlePinyin) { this.titlePinyin = titlePinyin; }
        @JsonIgnore
        public String getTitlePinyinInitials() { return titlePinyinInitials; }
        public void setTitlePinyinInitials(String titlePinyinInitials) { this.titlePinyinInitials = titlePinyinInitials; }
        @JsonIgnore
        public String getTitleWordInitials() { return titleWordInitials; }
        public void setTitleWordInitials(String titleWordInitials) { this.titleWordInitials = titleWordInitials; }
        @JsonIgnore
        public String getArtistPinyin() { return artistPinyin; }
        public void setArtistPinyin(String artistPinyin) { this.artistPinyin = artistPinyin; }
        @JsonIgnore
        public String getArtistPinyinInitials() { return artistPinyinInitials; }
        public void setArtistPinyinInitials(String artistPinyinInitials) { this.artistPinyinInitials = artistPinyinInitials; }
        @JsonIgnore
        public String getArtistWordInitials() { return artistWordInitials; }
        public void setArtistWordInitials(String artistWordInitials) { this.artistWordInitials = artistWordInitials; }
        @JsonIgnore
        public String getAlbumPinyin() { return albumPinyin; }
        public void setAlbumPinyin(String albumPinyin) { this.albumPinyin = albumPinyin; }
    }

    private record SearchResponse(boolean success, String message, Object results) {
    }

}
