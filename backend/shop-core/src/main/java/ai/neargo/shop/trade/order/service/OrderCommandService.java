package ai.neargo.shop.trade.order.service;

import ai.neargo.shop.trade.order.command.CreateOrderCommand;
import ai.neargo.shop.trade.order.command.ShipCommand;
import ai.neargo.shop.trade.order.command.ShipResult;
import ai.neargo.shop.trade.order.contract.UnifiedOrderVO;

/**
 * 订单<b>写命令</b>（下单/支付/发货/收货/取消）。
 *
 * <p><b>本阶段只定接口，不提供实现</b>（strangler 第一步）。写路径实现落在后续阶段：
 * 下单按商家拆、发货建发货单（ord_shipment）、行级退款，均依赖领域假设①~⑥定稿与迁移，
 * 且会替换现有写入 → 需灰度。届时实现类接 {@code OrderTransitions}（唯一写入门）。
 */
public interface OrderCommandService {

    /** 下单：按商家拆 → 支付单 + 订单 + 订单行 + 锁库存；返回统一订单。 */
    UnifiedOrderVO create(CreateOrderCommand cmd, String idemKey);

    /** 支付成功回调：支付单→PAID（终态），其下订单→待履约/履约中，出码实扣。 */
    void markPaid(String payNo, String channel, String tradeNo);

    /** 商家发货：建发货单、勾选行归属、订单→FULFILLING；支持部分发货。 */
    ShipResult ship(String orderNo, ShipCommand cmd);

    /** 买家确认收货：发货单→RECEIVED；全部交付则订单→COMPLETED。 */
    void confirmReceipt(String orderNo, String packageId, String userNo);

    /** 取消订单：仅未付款；已付款取消走售后退款。 */
    void cancel(String orderNo, String reason, String userNo);
}
