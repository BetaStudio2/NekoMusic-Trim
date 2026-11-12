package com.neko.music.config;

/**
 * 一条可在后台「系统设置」中编辑的配置项。
 *
 * <p>{@code key} 就是 config.yml 里的点分路径（如 {@code video_render.worker_threads}）。
 * 之所以沿用路径而不是另起一套键名：数据库里的覆盖值可以按路径直接合并进配置树，
 * 由 {@link ConfigManager} 现有的解析逻辑读到，不必为每个配置项再写一遍 setter，
 * 也就不存在「注册表加了配置项但忘了接上读取端」的漏配。</p>
 *
 * <p>{@code defaultValue} 与 {@code src/main/resources/config.yml} 中的出厂值保持一致：
 * 首次启动、或某个键在配置树里缺省时，用它兜底。</p>
 *
 * @param key             点分配置路径，也是数据库主键
 * @param group           后台页面分组
 * @param label           中文名
 * @param type            值类型，决定校验与前端控件
 * @param defaultValue    出厂默认值（统一用字符串表示）
 * @param secret          是否敏感：接口回显时掩码，不返回明文
 * @param restartRequired 是否必须重启后端才生效（端口、线程池、连接池等启动期绑定项）
 * @param min             数值下限，null 表示不限制
 * @param max             数值上限，null 表示不限制
 * @param description     一句话说明，展示在后台
 */
public record SystemSettingDefinition(
        String key,
        Group group,
        String label,
        Type type,
        String defaultValue,
        boolean secret,
        boolean restartRequired,
        Long min,
        Long max,
        String description) {

    /** 值类型。 */
    public enum Type {
        STRING,
        INT,
        LONG,
        DOUBLE,
        BOOL,
        /** 多值列表，数据库里用换行分隔。 */
        LIST
    }

    /** 后台页面分组。 */
    public enum Group {
        SERVER("服务器"),
        REDIS("Redis"),
        JWT("登录令牌"),
        MAIL("邮件"),
        REGISTER("注册与验证"),
        RATE_LIMIT("访问频率限制"),
        NETWORK("网络与安全"),
        NOTIFY("消息通知"),
        STORAGE("存储"),
        VIDEO_RENDER("视频渲染"),
        AUTH("NekoAuth 登录"),
        EXTERNAL("外站补全"),
        RECOMMENDATION("每日推荐"),
        RECOGNITION("听歌识曲"),
        SEARCH("本地搜索"),
        PAY("支付"),
        LOGGING("日志");

        private final String title;

        Group(String title) {
            this.title = title;
        }

        public String getTitle() {
            return title;
        }
    }

    /** 普通文本。 */
    public static SystemSettingDefinition text(String key, Group group, String label,
                                               String defaultValue, String description) {
        return new SystemSettingDefinition(key, group, label, Type.STRING,
                defaultValue, false, false, null, null, description);
    }

    /** 敏感文本：只写不读，接口回显为掩码。 */
    public static SystemSettingDefinition secret(String key, Group group, String label,
                                                 String description) {
        return new SystemSettingDefinition(key, group, label, Type.STRING,
                "", true, false, null, null, description);
    }

    /** 开关。 */
    public static SystemSettingDefinition flag(String key, Group group, String label,
                                               boolean defaultValue, String description) {
        return new SystemSettingDefinition(key, group, label, Type.BOOL,
                Boolean.toString(defaultValue), false, false, null, null, description);
    }

    /** 整数。 */
    public static SystemSettingDefinition number(String key, Group group, String label,
                                                 String defaultValue, long min, long max,
                                                 String description) {
        return new SystemSettingDefinition(key, group, label, Type.INT,
                defaultValue, false, false, min, max, description);
    }

    /** 小数。 */
    public static SystemSettingDefinition decimal(String key, Group group, String label,
                                                  String defaultValue, long min, long max,
                                                  String description) {
        return new SystemSettingDefinition(key, group, label, Type.DOUBLE,
                defaultValue, false, false, min, max, description);
    }

    /** 列表，数据库里按行分隔。 */
    public static SystemSettingDefinition list(String key, Group group, String label,
                                               String defaultValue, String description) {
        return new SystemSettingDefinition(key, group, label, Type.LIST,
                defaultValue, false, false, null, null, description);
    }

    /** 标记为「必须重启后端才生效」。 */
    public SystemSettingDefinition restart() {
        return new SystemSettingDefinition(key, group, label, type, defaultValue,
                secret, true, min, max, description);
    }
}
