package com.neko.music.filter;

import com.neko.music.util.OperationAudit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * 操作日志的模块 / 动作 / 目标归类。
 *
 * <p>这是「后台到底做了什么」唯一的人话来源，归错就等于没记；其中 {@code /api/music/*} 上的
 * 管理员音乐上传 / 修改 / 删除最容易被漏掉——它们的路径不在 {@code /api/admin/*} 下。</p>
 */
class RequestAuditFilterTest {

    @Test
    @DisplayName("系统设置 / 审核 / 歌词 / 用户 / 版本 的写操作有语义化动作")
    void resolvesSemanticActionsForAdminWrites() {
        assertRule("PUT", "/api/admin/settings", "系统设置", "修改系统设置");
        assertRule("POST", "/api/admin/audit/approve/37", "审核管理", "审核通过");
        assertRule("POST", "/api/admin/audit/reject/37", "审核管理", "审核驳回");
        assertRule("PUT", "/api/admin/lyrics-files/file/148.lrc", "歌词管理", "修改歌词");
        assertRule("DELETE", "/api/admin/lyrics-files/file/148.lrc", "歌词管理", "删除歌词");
        assertRule("PUT", "/api/admin/users/12/edit", "用户管理", "修改用户信息");
        assertRule("DELETE", "/api/admin/users/12", "用户管理", "删除用户");
        assertRule("PUT", "/api/users/12/edit", "用户管理", "修改用户信息");
        assertRule("DELETE", "/api/users/12", "用户管理", "删除用户");
        assertRule("POST", "/api/admin/create", "管理员管理", "创建管理员账号");
        assertRule("POST", "/api/admin/create-user", "用户管理", "创建用户");
        assertRule("PUT", "/api/admin/releases", "客户端更新", "更新客户端版本号");
        assertRule("POST", "/api/admin/releases/upload", "客户端更新", "上传客户端安装包");
        assertRule("PUT", "/api/admin/vip/pricing", "VIP 定价", "修改 VIP 价目");
        assertRule("POST", "/api/admin/netease/qr/key", "外站补全", "申请网易云扫码登录");
        assertRule("POST", "/api/admin/netease/logout", "外站补全", "退出网易云登录");
        assertRule("POST", "/api/admin/qq/qr/key", "外站补全", "申请 QQ 音乐扫码登录");
        assertRule("POST", "/api/admin/qq/logout", "外站补全", "退出 QQ 音乐登录");
        assertRule("POST", "/api/admin/login", "管理员登录", "登录");
        assertRule("POST", "/api/admin/logout", "管理员登录", "登出");
    }

    @Test
    @DisplayName("音乐的上传 / 修改 / 删除挂在 /api/music/*，必须纳入操作日志")
    void resolvesMusicOperationsOutsideAdminPrefix() {
        assertRule("POST", "/api/music/upload", "音乐管理", "上传音乐");
        assertRule("PUT", "/api/music/upload", "音乐管理", "修改音乐");
        assertRule("POST", "/api/music", "音乐管理", "新增音乐");
        assertRule("PUT", "/api/music/148", "音乐管理", "修改音乐");
        assertRule("DELETE", "/api/music/148", "音乐管理", "删除音乐");
        assertRule("DELETE", "/api/music/delete/148", "音乐管理", "删除音乐");
    }

    @Test
    @DisplayName("公开接口不算操作：听歌识曲、搜索、读取一律无规则")
    void publicEndpointsHaveNoOperationRule() {
        assertNull(RequestAuditFilter.resolveRule("/api/music/recognize", "POST"));
        assertNull(RequestAuditFilter.resolveRule("/api/music/search", "POST"));
        assertNull(RequestAuditFilter.resolveRule("/api/music/148", "GET"));
        assertNull(RequestAuditFilter.resolveRule("/api/music/recognize", "PUT"));
        assertNull(RequestAuditFilter.resolveRule("/api/admin/settings", "GET"));
        assertNull(RequestAuditFilter.resolveRule("/api/admin/audit/pending", "GET"));
        assertNull(RequestAuditFilter.resolveRule("/api/admin/logs/access", "GET"));
    }

    @Test
    @DisplayName("未登记规则的后台写操作：模块兜底 + 通用动作，仍然留痕")
    void fallsBackForUnregisteredAdminWrites() {
        assertEquals("管理后台", RequestAuditFilter.moduleFor("/api/admin/something-new", null));
        assertEquals("更新", RequestAuditFilter.actionFor("/api/admin/something-new", "PUT", null));
        assertEquals("删除", RequestAuditFilter.actionFor("/api/admin/something-new", "DELETE", null));
        // 已登记的路径优先给出语义化模块名
        assertEquals("歌词管理", RequestAuditFilter.moduleFor("/api/admin/lyrics-files/file/1.lrc",
                RequestAuditFilter.resolveRule("/api/admin/lyrics-files/file/1.lrc", "PUT")));
    }

    @Test
    @DisplayName("操作目标：取路径里最后一段纯数字，便于看出改的是哪一条")
    void extractsTargetDetail() {
        assertEquals("#148", RequestAuditFilter.targetDetail("/api/music/148"));
        assertEquals("#148", RequestAuditFilter.targetDetail("/api/admin/lyrics-files/file/148.lrc"));
        assertEquals("#37", RequestAuditFilter.targetDetail("/api/admin/audit/approve/37"));
        assertEquals("#12", RequestAuditFilter.targetDetail("/api/admin/users/12/edit"));
        assertNull(RequestAuditFilter.targetDetail("/api/admin/settings"));
        assertNull(RequestAuditFilter.targetDetail("/api/music/upload"));
        assertNull(RequestAuditFilter.targetDetail(null));
    }

    @Test
    @DisplayName("操作说明：优先用处理器挂上来的变更明细，没有明细才退回路径里的编号")
    void detailPrefersRecordedChanges() {
        assertEquals("#148", RequestAuditFilter.operationDetail(List.of(), "/api/music/148"));
        assertNull(RequestAuditFilter.operationDetail(List.of(), "/api/admin/settings"));
        assertEquals("运行端口：65535 → 8080", RequestAuditFilter.operationDetail(
                List.of(new OperationAudit.Change("运行端口", "65535", "8080")),
                "/api/admin/settings"));
        // 有明细时不再退回编号：明细更具体，「改了什么」比「改的是哪一条」重要
        assertEquals("名称：晴天 → 晴 天", RequestAuditFilter.operationDetail(
                List.of(new OperationAudit.Change("名称", "晴天", "晴 天")), "/api/music/148"));
    }

    @Test
    @DisplayName("查询串脱敏：令牌等一次性凭证的值不落库，其余查询串原样保留")
    void redactsSensitiveQueryValues() {
        // SSE 接口只能把管理员令牌放在查询串里，落库必须脱敏
        assertEquals("token=***",
                RequestAuditFilter.redactQuery("token=abc123"));
        assertEquals("q=%E6%99%B4%E5%A4%A9&page=2",
                RequestAuditFilter.redactQuery("q=%E6%99%B4%E5%A4%A9&page=2"));
        assertEquals("size=50&token=***&keyword=x",
                RequestAuditFilter.redactQuery("size=50&token=abc&keyword=x"));
        assertEquals("TOKEN=***", RequestAuditFilter.redactQuery("TOKEN=abc"));
        assertEquals("code=***&t=a", RequestAuditFilter.redactQuery("code=9999&t=a"));
        // 无值 / 无查询串不改变形状
        assertEquals("token", RequestAuditFilter.redactQuery("token"));
        assertEquals("a=1&", RequestAuditFilter.redactQuery("a=1&"));
        assertNull(RequestAuditFilter.redactQuery(null));
        assertEquals("", RequestAuditFilter.redactQuery(""));
    }

    private static void assertRule(String method, String path, String module, String action) {
        RequestAuditFilter.OperationRule rule = RequestAuditFilter.resolveRule(path, method);
        assertNotNull(rule, method + " " + path + " 应命中操作规则");
        assertEquals(module, rule.module(), method + " " + path + " 的模块");
        assertEquals(action, rule.action(), method + " " + path + " 的动作");
    }
}
