package ai.neargo.shop.trade.service;

import ai.neargo.shop.trade.entity.OrdSubOrder;

/**
 * 履约进度 —— 售后「货还能不能拦下来」的判据（TDD-售后规则-审核与部分退款 §2.1）。
 *
 * <p>订单状态字段表达不了中间两段：录运单号那一刻子单就置 {@code FULFILLING}
 * （{@code MerchantOrderServiceImpl}），于是「已发货」既可能是「刚录单号、快递没来取、货还在仓里」，
 * 也可能是「商家为生鲜采购甚至送出、但还没录单号」时的 {@code WAIT_FULFILL}。
 * 能不能退取决于货还拦不拦得住，不是订单状态字段 —— 这里把
 * 子单状态 + 备货时刻 + 运单状态合成五档。
 *
 * <p><b>纯函数，数据来源归调用方。</b> 运单状态来自 {@code LogisticsPort.TrackView#status}
 * （没有结构化的揽收时刻，故用 status 判揽收）；运单创建时刻由调用方从运单查。
 */
public enum FulfillStage {
    /** 未备货：货没动，自由退 */
    NOT_PREPARED,
    /** 备货中：商家已为这单采购/分拣，货在商家手里（生鲜已付出成本）—— 灰色地带，商家确认 */
    PREPARING,
    /** 已出单未揽收：录了运单号但快递还没来取，货还在仓里可能拦得住 —— 灰色地带，商家确认 */
    LABELED,
    /** 已揽收·在途：快递取走了，拦不住 */
    SHIPPED,
    /** 已签收：买家手里 */
    RECEIVED;

    /**
     * 录了运单号但渠道迟迟没回「已揽收」（status 停在 CREATED）：超过这个时长就从严当已发出。
     * 宁可让商家裁量，也不自动放行一单「其实早发走了只是没回传揽收」的生鲜（§4 R7）。
     */
    static final long LABEL_GRACE_MS = 6 * 3600_000L;

    /** 运单状态的 wire 值：录单即 {@code CREATED}，揽收后进 {@code PICKED_UP} 及以后（见前端 ShipmentStatus）。 */
    private static final String WAYBILL_CREATED = "CREATED";

    /**
     * @param subStatus        子单状态（{@link OrdSubOrder} 的值，如 {@code WAIT_FULFILL}/{@code FULFILLING}/{@code COMPLETED}）
     * @param preparedAt       商家开始备货时刻(ms)；未备货为 {@code null}
     * @param waybillStatus    运单状态 wire 值；无运单为 {@code null}/空
     * @param waybillCreatedAt 运单创建时刻(ms)；无运单为 {@code null}
     * @param now              当前时刻(ms)
     */
    public static FulfillStage of(String subStatus, Long preparedAt,
                                  String waybillStatus, Long waybillCreatedAt, long now) {
        // 签收是终点，优先级最高：即便运单状态还没回到 DELIVERED，子单 COMPLETED 就是收到了
        if (OrdSubOrder.COMPLETED.equals(subStatus)) {
            return RECEIVED;
        }
        boolean hasWaybill = waybillStatus != null && !waybillStatus.isBlank();
        if (hasWaybill) {
            // 非 CREATED = 已进入揽收及以后，拦不住
            if (!WAYBILL_CREATED.equals(waybillStatus)) {
                return SHIPPED;
            }
            // 录了单号但还停在 CREATED：宽限期内算「已出单未揽收」，超期从严当已发出
            if (waybillCreatedAt != null && now - waybillCreatedAt >= LABEL_GRACE_MS) {
                return SHIPPED;
            }
            return LABELED;
        }
        // 没有运单：看商家点没点「开始备货」
        return preparedAt != null ? PREPARING : NOT_PREPARED;
    }
}
