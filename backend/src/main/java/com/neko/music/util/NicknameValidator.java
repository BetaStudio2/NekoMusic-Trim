package com.neko.music.util;

/**
 * 昵称校验：注册与修改昵称共用同一套规则（长度 1-32，且不包含控制字符或违禁词）。
 */
public final class NicknameValidator {

    /** 昵称最大长度 */
    public static final int MAX_LENGTH = 32;

    private NicknameValidator() {
    }

    /**
     * 校验昵称是否合法。
     *
     * @param nickname 已 trim 的昵称
     * @return 合法时返回 {@code null}，否则返回错误提示
     */
    public static String validate(String nickname) {
        if (nickname == null || nickname.isEmpty() || nickname.length() > MAX_LENGTH) {
            return "昵称长度需在1-32之间喵";
        }
        if (nickname.chars().anyMatch(Character::isISOControl)) {
            return "昵称包含非法字符喵";
        }
        if (SensitiveWordUtil.contains(nickname)) {
            return "昵称包含违禁词喵";
        }
        return null;
    }
}
