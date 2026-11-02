package com.neko.music.model;

import java.sql.Timestamp;

/**
 * 用户。只读数据载体（JDBC 行映射后即使用，不再修改）。
 *
 * <p>昵称只用于运行时与对外 JSON，字段名统一为 {@code nickname}；MySQL 不保存该列。
 * 保留 {@code (nickname, password, email)} 便捷构造器供注册使用。Jackson 对
 * boolean 组件的序列化名仍为 {@code emailVerified}（与原 {@code isEmailVerified()} 相同）。
 */
public record User(
        int id,
        String nickname,
        String password,
        String email,
        boolean emailVerified,
        String avatar,
        String createdAt,
        Timestamp vipExpiresAt) {

    public User(String nickname, String password, String email) {
        this(0, nickname, password, email, false, null, null, null);
    }
}
