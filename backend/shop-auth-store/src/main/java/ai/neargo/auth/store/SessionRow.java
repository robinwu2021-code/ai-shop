package ai.neargo.auth.store;

import java.time.LocalDateTime;

/**
 * 会话表的一行。**八列，到此为止 —— 它只回答「这个令牌属于谁、还有效吗」。**
 *
 * <p>刻意不含 nickname / roles / perms / tenantNo / scope：
 * 那些变动频率高于会话生命期（会话活 30 天，角色可能今天改）。
 * 存进来就有第二个真源，而过期的那一份<b>不会报错，只会让人拥有他昨天的权限</b>。
 * 身份由 {@link IdentityLoader} 从用户表现读现算。
 *
 * <p>也不含 realm：表本身就是按端分的，再存一列等于允许
 * 「运营端的行出现在 C 端表里」这种状态存在。
 *
 * @param tokenHash    SHA-256(token) 的十六进制。**明文令牌不入库**
 * @param revokedAt    软撤销。删行会让「没这行」和「被踢了」分不开，
 *                     而「我为什么突然被登出」是最常被问到的问题之一
 */
public record SessionRow(
        Long id,
        String tokenHash,
        String userNo,
        /** 上面那个号<b>属于哪张表</b>。见 {@code SubjectKind} —— 与「哪个池」是两件事。 */
        String subjectKind,
        LocalDateTime issuedAt,
        LocalDateTime expiresAt,
        LocalDateTime lastSeenAt,
        LocalDateTime revokedAt,
        String revokeReason) {

    /** 还有效吗。**用应用时钟判定，不用 SQL 的 NOW()** —— 见 SessionDao 类注释。 */
    public boolean isLive(LocalDateTime now) {
        return revokedAt == null && expiresAt != null && expiresAt.isAfter(now);
    }
}
