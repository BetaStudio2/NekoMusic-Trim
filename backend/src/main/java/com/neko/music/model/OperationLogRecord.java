package com.neko.music.model;

/**
 * 一条后台操作日志：谁（管理员）、什么时候、对哪个模块做了什么、结果如何。
 *
 * @param occurredAt    操作时间（epoch 毫秒）
 * @param adminId       管理员 ID（未登录/令牌失效时为 null）
 * @param adminUsername 管理员用户名
 * @param adminRole     管理员角色
 * @param module        业务模块（如「用户管理」）
 * @param action        动作（创建/更新/删除/登录/…）
 * @param method        HTTP 方法
 * @param path          路由路径
 * @param query         原始查询串
 * @param status        响应状态码
 * @param success       是否成功（2xx/3xx）
 * @param durationUs    处理耗时（微秒）
 * @param clientIp      可信客户端 IP
 * @param userAgent     原始 User-Agent
 * @param detail        补充说明
 */
public record OperationLogRecord(
        long occurredAt,
        Integer adminId,
        String adminUsername,
        String adminRole,
        String module,
        String action,
        String method,
        String path,
        String query,
        int status,
        boolean success,
        long durationUs,
        String clientIp,
        String userAgent,
        String detail) {
}
