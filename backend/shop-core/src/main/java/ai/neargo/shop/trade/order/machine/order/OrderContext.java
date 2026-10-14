package ai.neargo.shop.trade.order.machine.order;

/**
 * 订单迁移要读的事实。
 *
 * @param authorized          执行者对这张单有权限（本人 / 本店 / 本自提点 / 运营干预权限）
 * @param serviceClass        服务类（付款即出码，直接履约中）
 * @param prepared            已点过「开始备货」
 * @param openAfterSale       有进行中的售后
 * @param requireBuyerConfirm 降级单：核销只推进到履约中，由买家确认收口
 * @param allLinesShipped     所有行都已发出（确认收货的前提）
 * @param autoReceiptDue      签收 / 发货满自动确认收货的天数
 * @param fullyRefunded       退款账本：全部行已退完
 * @param interveneTarget     人工干预要去的状态（仅 INTERVENE 用）
 * @param remarkPresent       人工干预写了备注
 */
public record OrderContext(boolean authorized, boolean serviceClass, boolean prepared, boolean openAfterSale,
                           boolean requireBuyerConfirm, boolean allLinesShipped, boolean autoReceiptDue,
                           boolean fullyRefunded, OrderStatus interveneTarget, boolean remarkPresent) {

    /** 只关心权限的场景（测试与简单调用方） */
    public static OrderContext authorizedOnly() {
        return new OrderContext(true, false, false, false, false, true, false, false, null, false);
    }

    public OrderContext withServiceClass(boolean v) {
        return new OrderContext(authorized, v, prepared, openAfterSale, requireBuyerConfirm, allLinesShipped,
                autoReceiptDue, fullyRefunded, interveneTarget, remarkPresent);
    }

    public OrderContext withOpenAfterSale(boolean v) {
        return new OrderContext(authorized, serviceClass, prepared, v, requireBuyerConfirm, allLinesShipped,
                autoReceiptDue, fullyRefunded, interveneTarget, remarkPresent);
    }

    public OrderContext withRequireBuyerConfirm(boolean v) {
        return new OrderContext(authorized, serviceClass, prepared, openAfterSale, v, allLinesShipped,
                autoReceiptDue, fullyRefunded, interveneTarget, remarkPresent);
    }

    public OrderContext withAutoReceiptDue(boolean v) {
        return new OrderContext(authorized, serviceClass, prepared, openAfterSale, requireBuyerConfirm,
                allLinesShipped, v, fullyRefunded, interveneTarget, remarkPresent);
    }

    public OrderContext withIntervene(OrderStatus target, boolean remark) {
        return new OrderContext(authorized, serviceClass, prepared, openAfterSale, requireBuyerConfirm,
                allLinesShipped, autoReceiptDue, fullyRefunded, target, remark);
    }
}
