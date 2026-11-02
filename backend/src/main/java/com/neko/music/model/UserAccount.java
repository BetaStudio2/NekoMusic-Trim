package com.neko.music.model;

/**
 * 管理端展示的普通用户账号。不要在这里放口令哈希等敏感字段。
 */
public record UserAccount(
        int id,
        String nickname,
        String email,
        String registerTime,
        boolean vip,
        String vipExpiresAt,
        Long authUserId) {

    public UserAccount(int id, String nickname, String email, String registerTime, boolean vip,
                       String vipExpiresAt) {
        this(id, nickname, email, registerTime, vip, vipExpiresAt, null);
    }
}
