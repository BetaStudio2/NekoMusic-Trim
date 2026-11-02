package com.neko.music.util;

import com.neko.music.Main;

/**
 * 用户信息查询辅助。
 *
 * <p>原先前端歌单接口的处理器各自复制了一份昵称查询，收敛到这里。
 */
public final class UserLookup {

    private UserLookup() {
    }

    /** 按用户 id 从 NekoAuth 实时解析昵称，查不到时返回「未知用户」。 */
    public static String getNickname(int userId) {
        return Main.getNicknameResolver().resolveByLocalUserId(userId, "未知用户");
    }
}
