package com.neko.music.model;

import java.sql.Timestamp;

/**
 * 当前登录用户的资料快照。口令哈希等敏感字段不属于这个模型。
 */
public record UserProfile(
        int id,
        String nickname,
        String email,
        String createdAt,
        Timestamp vipExpiresAt,
        Long authUserId) {

    public UserProfile(int id, String nickname, String email, String createdAt, Timestamp vipExpiresAt) {
        this(id, nickname, email, createdAt, vipExpiresAt, null);
    }
}
