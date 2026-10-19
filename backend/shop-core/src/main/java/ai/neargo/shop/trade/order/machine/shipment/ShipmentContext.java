package ai.neargo.shop.trade.order.machine.shipment;

/**
 * 发货单迁移要读的事实。
 *
 * @param authorized   本店的单
 * @param waybillValid 运单号清洗后格式合法
 * @param waybillFree  运单号没有被别的订单占用（同单同号在调用方按幂等处理）
 * @param dispatched   子单已履约且本包裹有实际已发订单行，不伪造承运商揽收
 */
public record ShipmentContext(boolean authorized, boolean waybillValid, boolean waybillFree, boolean dispatched) {

    /** Existing registration/correction contexts do not assert physical dispatch. */
    public ShipmentContext(boolean authorized, boolean waybillValid, boolean waybillFree) {
        this(authorized, waybillValid, waybillFree, false);
    }

    public static ShipmentContext ok() {
        return new ShipmentContext(true, true, true);
    }
}
