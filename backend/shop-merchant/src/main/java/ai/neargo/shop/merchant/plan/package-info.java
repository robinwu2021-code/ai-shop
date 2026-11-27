/**
 * 主体套餐：套餐定义、主体订阅（额度）、旧的主体套餐购买与退款。
 *
 * <p><b>待退役（2026-11-26 用户定）：旧的主体套餐购买路径并入商城</b>，由负责商城的会话处理，本分支只做标注、不改写。
 * 涉及 {@link ai.neargo.shop.merchant.plan.EntityPlanPurchaseService}、
 * {@link ai.neargo.shop.merchant.plan.EntityPlanRefundService}、{@code EntityPlanPurchasePolicy} 及其实现。
 * 退役之前它仍在一个事务里锁平台基础库的主体、账号行并恢复门店营业，是平台基础库换表的越界点之一
 * （{@code known-platform-table-access.txt}）；退役后随之消失。
 *
 * <p>按「购买与开通是两件事」：购买在商城，恢复门店营业属于开通，在平台基础库单独执行。
 */
package ai.neargo.shop.merchant.plan;
