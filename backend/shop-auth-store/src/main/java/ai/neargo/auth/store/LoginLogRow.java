package ai.neargo.auth.store;

import java.time.LocalDateTime;

/**
 * 登录日志一行。
 *
 * <p><b>IP 与 UA 记在这里，不记在会话表</b>：会话表将来要被多个服务读，
 * 而这张表访问面窄、保留期短。同一份 PII，放对地方就不是问题。
 */
public record LoginLogRow(
        Long id,
        LocalDateTime at,
        String event,
        String userNo,
        boolean success,
        String reason,
        String clientIp,
        String userAgent) {
}
