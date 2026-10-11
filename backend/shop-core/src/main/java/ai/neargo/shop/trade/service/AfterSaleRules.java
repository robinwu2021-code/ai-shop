package ai.neargo.shop.trade.service;

import ai.neargo.shop.trade.entity.OrdAfterSale;

import java.util.List;

/**
 * 售后可用性规则（TDD-售后规则-审核与部分退款 §2.2）—— <b>后端这份与前端
 * {@code packages/shared/src/strategies/after-sale.ts} 必须逐条一致</b>，
 * 两边共用同一张用例表（§4 R4：判据分叉会造成前端放行、后端拒绝）。
 *
 * <p>核心：生鲜能不能退取决于 {@link FulfillStage}（货还拦不拦得住），不是订单状态。
 */
public final class AfterSaleRules {

    private AfterSaleRules() {
    }

    /** 质量原因：损坏 / 变质 / 过期。其余（不想要 / 缺件 / 错货 / 其他）为非质量。 */
    public static final List<String> QUALITY_REASONS = List.of(
            OrdAfterSale.REASON_DAMAGED, OrdAfterSale.REASON_QUALITY, OrdAfterSale.REASON_EXPIRED);

    /** 拒绝码（映射到 ErrorCode.AFTERSALE_FRESH_QUALITY_ONLY，前端据此提示）。 */
    public static final String DENY_FRESH_QUALITY_ONLY = "FRESH_SHIPPED_NON_QUALITY";

    public static boolean isQualityReason(String reason) {
        return QUALITY_REASONS.contains(reason);
    }

    /**
     * 申请结论。
     *
     * @param allow        能否建单
     * @param needMerchant 灰色地带：可建单但必须商家确认、不参与超时自动同意
     * @param deny         不允许时的拒绝码；允许时为 null
     */
    public record Gate(boolean allow, boolean needMerchant, String deny) {
    }

    /**
     * 能否申请 + 是否灰色（§2.2 矩阵）：
     * <ul>
     *   <li>生鲜 + <b>已揽收或已签收</b> + 非质量 → 拒（{@link #DENY_FRESH_QUALITY_ONLY}）</li>
     *   <li>生鲜 + <b>备货中或已出单未揽收</b>（灰色）→ 允许，但需商家确认</li>
     *   <li>其余 → 允许</li>
     * </ul>
     */
    public static Gate applyGate(FulfillStage stage, boolean fresh, String reason) {
        boolean shippedOrReceived = stage == FulfillStage.SHIPPED || stage == FulfillStage.RECEIVED;
        if (fresh && shippedOrReceived && !isQualityReason(reason)) {
            return new Gate(false, false, DENY_FRESH_QUALITY_ONLY);
        }
        if (fresh && (stage == FulfillStage.PREPARING || stage == FulfillStage.LABELED)) {
            return new Gate(true, true, null);
        }
        return new Gate(true, false, null);
    }

    /** 已签收才需要图片凭证；未收货（发货前/运输中）不显示上传框。 */
    public static boolean needImage(FulfillStage stage) {
        return stage == FulfillStage.RECEIVED;
    }

    /** 该档位可选的原因：生鲜在已揽收/已签收只剩质量三项，其余给全部。 */
    public static List<String> reasonsFor(FulfillStage stage, boolean fresh) {
        boolean onlyQuality = fresh
                && (stage == FulfillStage.SHIPPED || stage == FulfillStage.RECEIVED);
        return onlyQuality ? QUALITY_REASONS : OrdAfterSale.REASONS;
    }
}
