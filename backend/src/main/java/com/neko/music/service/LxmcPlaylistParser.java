package com.neko.music.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.PushbackInputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.zip.GZIPInputStream;

/**
 * 落雪音乐（LX Music）导出的 {@code .lxmc} 歌单文件解析器。
 *
 * <p>文件本体是 gzip 压缩的 JSON，常见结构为
 * {@code {"type":"playList_v2","data":[{"id":"...","name":"...","list":[...]}]}}。
 * 这里只提取站内导入需要的歌名、歌手、原始音源与来源 ID，不接受文件中的封面、歌词等大字段
 * 作为业务输入。</p>
 *
 * <p>上传文件在进入 JSON 解析前同时限制压缩大小与解压后大小，避免 gzip 解压炸弹占用服务端内存。</p>
 */
public final class LxmcPlaylistParser {
    public static final int MAX_COMPRESSED_BYTES = 4 * 1024 * 1024;
    public static final int MAX_DECOMPRESSED_BYTES = 16 * 1024 * 1024;

    private static final int MAX_PLAYLISTS = 100;
    private static final int MAX_TRACKS = 20_000;

    private final ObjectMapper objectMapper;

    public LxmcPlaylistParser() {
        this(new ObjectMapper());
    }

    LxmcPlaylistParser(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public record Track(String source, String sourceId, String title, String artist) {
    }

    public record Playlist(String id, String name, List<Track> tracks, Map<String, Integer> sourceCounts) {
        public Playlist {
            tracks = List.copyOf(tracks);
            sourceCounts = Collections.unmodifiableMap(new LinkedHashMap<>(sourceCounts));
        }
    }

    public record Document(List<Playlist> playlists, int totalTrackCount) {
        public Document {
            playlists = List.copyOf(playlists);
        }
    }

    public static class LxmcFormatException extends Exception {
        public LxmcFormatException(String message) {
            super(message);
        }

        public LxmcFormatException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    public static final class LxmcTooLargeException extends LxmcFormatException {
        public LxmcTooLargeException(String message) {
            super(message);
        }
    }

    public Document parse(InputStream uploaded) throws IOException, LxmcFormatException {
        if (uploaded == null) {
            throw new LxmcFormatException("缺少上传文件");
        }

        byte[] jsonBytes = readJsonBytes(uploaded);
        JsonNode root;
        try {
            root = objectMapper.readTree(jsonBytes);
        } catch (IOException e) {
            throw new LxmcFormatException("JSON 解析失败", e);
        }
        if (root == null || !root.isObject()) {
            throw new LxmcFormatException("文件根节点不是 JSON 对象");
        }

        JsonNode data = root.path("data");
        if (!data.isArray()) {
            throw new LxmcFormatException("文件缺少 data 歌单数组");
        }
        if (data.size() > MAX_PLAYLISTS) {
            throw new LxmcFormatException("歌单数量超过限制");
        }

        List<Playlist> playlists = new ArrayList<>(data.size());
        int totalTracks = 0;
        int playlistIndex = 0;
        for (JsonNode playlistNode : data) {
            playlistIndex++;
            if (!playlistNode.isObject()) {
                continue;
            }
            JsonNode entries = firstArray(playlistNode, "list", "songs", "tracks", "musicList");
            if (entries == null) {
                entries = objectMapper.createArrayNode();
            }
            int remaining = MAX_TRACKS - totalTracks;
            if (entries.size() > remaining) {
                throw new LxmcTooLargeException("曲目数量超过限制");
            }

            List<Track> tracks = new ArrayList<>(entries.size());
            Map<String, Integer> sourceCounter = new TreeMap<>();
            for (JsonNode entry : entries) {
                if (!entry.isObject()) {
                    continue;
                }
                String source = normalizeSource(text(entry, "source"), text(entry, "id"));
                String sourceId = firstText(
                        entry.path("meta").path("songId"),
                        entry.path("meta").path("hash"),
                        entry.path("meta").path("id"),
                        entry.path("hash"),
                        entry.path("id"));
                String title = firstText(
                        entry.path("name"),
                        entry.path("title"),
                        entry.path("songName"));
                String artist = firstText(
                        entry.path("singer"),
                        entry.path("artist"),
                        entry.path("singerName"));
                if (artist.isEmpty()) {
                    artist = joinArtists(entry.path("artists"));
                }
                tracks.add(new Track(source, sourceId, title, artist));
                sourceCounter.merge(source, 1, Integer::sum);
            }
            totalTracks += tracks.size();

            String id = text(playlistNode, "id");
            String name = text(playlistNode, "name");
            if (id.isEmpty()) {
                id = "lxmc-playlist-" + playlistIndex;
            }
            playlists.add(new Playlist(id, name, tracks, sourceCounter));
        }

        return new Document(playlists, totalTracks);
    }

    private byte[] readJsonBytes(InputStream uploaded) throws IOException, LxmcFormatException {
        PushbackInputStream probe = new PushbackInputStream(uploaded, 2);
        int first = probe.read();
        int second = probe.read();
        if (second != -1) {
            probe.unread(second);
        }
        if (first != -1) {
            probe.unread(first);
        }

        boolean gzip = first == 0x1f && second == 0x8b;
        try (InputStream content = gzip ? new GZIPInputStream(probe) : probe) {
            return readLimited(content, MAX_DECOMPRESSED_BYTES);
        } catch (LxmcTooLargeException e) {
            throw e;
        } catch (IOException e) {
            if (gzip) {
                throw new LxmcFormatException("gzip 压缩包损坏或不是有效的 .lxmc 文件", e);
            }
            throw e;
        }
    }

    private static byte[] readLimited(InputStream input, int maxBytes)
            throws IOException, LxmcTooLargeException {
        ByteArrayOutputStream output = new ByteArrayOutputStream(Math.min(maxBytes, 64 * 1024));
        byte[] buffer = new byte[16 * 1024];
        int total = 0;
        int read;
        while ((read = input.read(buffer)) != -1) {
            total += read;
            if (total > maxBytes) {
                throw new LxmcTooLargeException("解压后的文件超过限制");
            }
            output.write(buffer, 0, read);
        }
        return output.toByteArray();
    }

    private static JsonNode firstArray(JsonNode object, String... fieldNames) {
        for (String fieldName : fieldNames) {
            JsonNode node = object.path(fieldName);
            if (node.isArray()) {
                return node;
            }
        }
        return null;
    }

    private static String text(JsonNode object, String fieldName) {
        return scalarText(object.path(fieldName));
    }

    private static String firstText(JsonNode... nodes) {
        for (JsonNode node : nodes) {
            String value = scalarText(node);
            if (!value.isEmpty()) {
                return value;
            }
        }
        return "";
    }

    private static String scalarText(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull()
                || node.isContainerNode() || node.isBinary()) {
            return "";
        }
        return node.asText("").trim();
    }

    private static String joinArtists(JsonNode artists) {
        if (!artists.isArray()) {
            return "";
        }
        List<String> names = new ArrayList<>();
        for (JsonNode artist : artists) {
            String name = artist.isObject()
                    ? firstText(artist.path("name"), artist.path("title"))
                    : scalarText(artist);
            if (!name.isEmpty()) {
                names.add(name);
            }
        }
        return String.join(" / ", names);
    }

    private static String normalizeSource(String rawSource, String rawId) {
        String source = rawSource == null ? "" : rawSource.trim().toLowerCase(Locale.ROOT);
        if (!source.matches("[a-z0-9_-]{1,32}")) {
            int separator = rawId == null ? -1 : rawId.indexOf('_');
            source = separator > 0 ? rawId.substring(0, separator).toLowerCase(Locale.ROOT) : "";
        }
        if (!source.matches("[a-z0-9_-]{1,32}")) {
            return "unknown";
        }
        return source;
    }
}
