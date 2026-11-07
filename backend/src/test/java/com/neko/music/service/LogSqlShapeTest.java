package com.neko.music.service;

import com.neko.music.model.AccessLogRecord;
import com.neko.music.model.OperationLogRecord;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.RecordComponent;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 日志 INSERT 语句的「列 ↔ 占位符 ↔ 数据载体」三方一致性。
 *
 * <p>手工维护的列清单最容易出的错就是「加了字段忘了绑定」或「绑定顺序错位」——两者都不会编译报错，
 * 只会在运行期把 IP 写进 UA 之类的列里，而且很难发现。这里用静态校验把它挡在提交前。</p>
 */
class LogSqlShapeTest {

    private static final Pattern INSERT = Pattern.compile(
            "INSERT INTO \\w+ \\(([^)]*)\\)\\s*VALUES \\(([^)]*)\\)", Pattern.DOTALL);

    @Test
    @DisplayName("访问日志：列数 = 占位符数 = 记录字段数，且顺序与记录一致")
    void accessLogSqlShapeMatchesRecord() throws Exception {
        assertShape(AccessLogService.class, AccessLogRecord.class);
    }

    @Test
    @DisplayName("操作日志：列数 = 占位符数 = 记录字段数，且顺序与记录一致")
    void operationLogSqlShapeMatchesRecord() throws Exception {
        assertShape(OperationLogService.class, OperationLogRecord.class);
    }

    private static void assertShape(Class<?> serviceClass, Class<?> recordClass) throws Exception {
        Field field = serviceClass.getDeclaredField("INSERT_SQL");
        field.setAccessible(true);
        String sql = (String) field.get(null);

        Matcher matcher = INSERT.matcher(sql);
        assertTrue(matcher.find(), "解析 INSERT 语句失败: " + sql);
        List<String> columns = split(matcher.group(1));
        List<String> placeholders = split(matcher.group(2));
        assertEquals(columns.size(), placeholders.size(), "列数与占位符数不一致");

        List<String> fields = new ArrayList<>();
        for (RecordComponent component : recordClass.getRecordComponents()) {
            fields.add(camelToSnake(component.getName()));
        }
        assertEquals(columns, fields, "INSERT 列的顺序必须与记录字段一一对应");
        for (String placeholder : placeholders) {
            assertEquals("?", placeholder, "占位符只能是 ?");
        }
    }

    private static List<String> split(String raw) {
        List<String> parts = new ArrayList<>();
        for (String part : raw.split(",")) {
            String trimmed = part.trim();
            if (!trimmed.isEmpty()) {
                parts.add(trimmed);
            }
        }
        return parts;
    }

    private static String camelToSnake(String name) {
        return name.replaceAll("(?<!^)(?=[A-Z])", "_").toLowerCase(java.util.Locale.ROOT);
    }

    @Test
    @DisplayName("字段数与表列数一致（防止只改一边）")
    void recordFieldCountsAreStable() {
        assertEquals(28, AccessLogRecord.class.getRecordComponents().length);
        assertEquals(15, OperationLogRecord.class.getRecordComponents().length);
    }

    @Test
    @DisplayName("SSE 实时行与列表行同形：记录 → Map 的键序必须等于 INSERT 列序")
    void accessLogStreamMapMatchesColumns() throws Exception {
        Field field = AccessLogService.class.getDeclaredField("INSERT_SQL");
        field.setAccessible(true);
        String sql = (String) field.get(null);
        Matcher matcher = INSERT.matcher(sql);
        assertTrue(matcher.find(), "解析 INSERT 语句失败: " + sql);

        Map<String, Object> row = AccessLogService.toMap(sampleRecord());
        assertEquals(split(matcher.group(1)), new ArrayList<>(row.keySet()),
                "实时推送的行缺少列（后台表格按列名取值，缺列会直接显示为空）");
    }

    /** 造一条填满全部字段的记录：只要键序对得上，值本身不重要。 */
    private static AccessLogRecord sampleRecord() {
        return new AccessLogRecord(
                1L, "GET", "/api/music/search", "q=x", "api", 200, 12, 34L,
                "127.0.0.1", "127.0.0.0/24", "NekoMusic-android/1",
                "https://example.com", "https://example.com", "zh-CN", true,
                7, "登录用户", 7, "admin", "music.example.com", "https", "HTTP/1.1", "jetty-1",
                true, "normal", 0, null, 1);
    }
}
