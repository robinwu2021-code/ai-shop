package ai.neargo.shop.marketing.coupon.dto;

/**
 * 一次主动发放的记录（{@code GET /ops/coupon-issues}）。字段名对齐 ops-web 的 {@code CouponIssue}。
 *
 * @param couponName 券名<b>快照</b> —— 券改名或归档后这条记录仍要读得懂
 * @param targetDesc 当时写下的定向说明，自由文本。审计要看的就是这句话
 * @param count      本次发放张数
 * @param amount     本次占用的预算（分）= 张数 × 面额
 * @param operator   操作人。**客服也持有发券权限**（矩阵 §2.3），这一列不能省
 */
public record CouponIssueVO(String issueNo,
                            String couponNo,
                            String couponName,
                            String target,
                            String targetDesc,
                            int count,
                            long amount,
                            String operator,
                            long createdAt) {
}
