package ai.neargo.shop.trade.order.command;

import java.util.List;

/**
 * 发货命令。{@code itemIds}=本次发哪些行（空=剩余未发的全部，支持部分发货）；
 * carrier+waybillNo 经清洗/格式/重号校验后建发货单。本阶段仅定义。
 */
public record ShipCommand(
        List<String> itemIds,
        String carrier,
        String waybillNo) {
}
