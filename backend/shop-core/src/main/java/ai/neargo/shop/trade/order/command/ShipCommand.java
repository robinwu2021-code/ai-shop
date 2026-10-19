package ai.neargo.shop.trade.order.command;

import java.util.List;

/** Physical row IDs prevent duplicate SKU and gift rows from being mistaken for one item. */
public record ShipCommand(List<Long> itemIds, String carrier, String waybillNo) {
    public ShipCommand {
        itemIds = itemIds == null ? List.of() : List.copyOf(itemIds);
    }
}
