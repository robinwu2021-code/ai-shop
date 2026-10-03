package ai.neargo.shop.risk.dto;

/**
 * 黑名单记录（对应 ops-web 的 {@code BlacklistEntry}）。
 *
 * @param until  到期时间。**永远非空** —— 无期限拉黑没有申诉出口
 * @param active 是否生效中。申诉通过或到期后为 false，**记录仍在**
 */
public record BlacklistVO(String blackNo,
                          String subjectType,
                          String subject,
                          String subjectName,
                          String reason,
                          String until,
                          String appealStatus,
                          String appealReason,
                          String appealVerdict,
                          boolean active,
                          String createdAt) {
}
