package ai.neargo.shop.marketing.coupon.dto;

/**
 * 券模板的**运营治理视图**（{@code GET /ops/coupons}）。
 *
 * <p><b>为什么不复用 {@link CouponVO}</b>：那是 C 端领券中心的视图，
 * 带 {@code received}（我领没领）、{@code remain}（还剩几张）——
 * 这些是<b>买家视角</b>的字段。运营要回答的是另一组问题：
 * 谁发的、发了多少张、核销了多少、花了多少钱、还剩多少预算。
 *
 * <p>一个 VO 同时服务两端的代价是实测出来的：ops-web 的券模板页
 * 期望 {@code name/value/threshold/budget/issued/redeemed}，
 * 而后端给的是 {@code title/faceMinor/thresholdMinor/…}，
 * 页面上直接渲染出 <b>{@code undefined / undefined}</b>。
 * 先写的那一端会定下命名口径，后来的要么将就，要么对不上。
 *
 * <p>字段名<b>对齐 ops-web 的 {@code Coupon} 类型</b>，不是对齐 C 端 ——
 * 这个 VO 只有一个消费方，跟着它走。
 *
 * @param value        面额（分）；DISCOUNT 券是折扣万分比（8500 = 85 折）
 * @param threshold    使用门槛（分），0 = 无门槛
 * @param budget       预算上限（分），<b>0 = 不限</b>
 * @param issuedAmount 已发放金额（分）= 已领张数 × 面额。
 *                     折扣券按面额算不出来，返回 0 —— <b>宁可显示 0，
 *                     也不要编一个看着像真的数</b>
 * @param issued       已领取张数
 * @param redeemed     已核销张数（真正花掉的那部分）
 */
public record OpsCouponVO(String couponNo,
                          String name,
                          String type,
                          String status,
                          long value,
                          long threshold,
                          String funder,
                          String merchantNo,
                          long validFrom,
                          long validTo,
                          long budget,
                          long issuedAmount,
                          int issued,
                          int redeemed,
                          long createdAt) {
}
