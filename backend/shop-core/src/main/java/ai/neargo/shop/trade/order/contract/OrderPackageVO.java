package ai.neargo.shop.trade.order.contract;

import java.util.List;

/**
 * 发货分组（对买家）。内部由一张子单实现，但对外是"订单里的一个包裹"，
 * 用订单内 {@code packageId}（P1/P2…）标识，不暴露子单号（{@code subOrderNo} 仅内部字段）。
 */
public record OrderPackageVO(
        String packageId,
        String subOrderNo,
        String merchantNo,
        String merchantName,
        String storeNo,
        String status,
        String fulfillment,
        String expressCompany,
        String expressNo,
        String verifyCode,
        String receiverName,
        String receiverPhone,
        String receiverAddress,
        List<OrderLineVO> items) {
}
