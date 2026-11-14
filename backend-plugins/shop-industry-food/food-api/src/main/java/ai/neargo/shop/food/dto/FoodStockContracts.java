package ai.neargo.shop.food.dto;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/** Restaurant stock management transport objects; ownership is supplied by trusted context. */
public final class FoodStockContracts {
    private FoodStockContracts() {}
    public record CreateItem(String itemKind, String skuNo, String name, String baseUnit, String trackingMode,
            @tools.jackson.databind.annotation.JsonDeserialize(using=ai.neargo.shop.spi.inventory.MaterialCatalogFacts.WeighedDeserializer.class) Integer weighed) {
        public CreateItem(String itemKind,String skuNo,String name,String baseUnit,String trackingMode){this(itemKind,skuNo,name,baseUnit,trackingMode,0);}
    }
    public record UpdateItem(String name, String status, String trackingMode, Long version,
            @tools.jackson.databind.annotation.JsonDeserialize(using=ai.neargo.shop.spi.inventory.MaterialCatalogFacts.WeighedDeserializer.class) Integer weighed) {
        public UpdateItem(String name,String status,String trackingMode,Long version){this(name,status,trackingMode,version,null);}
    }
    public record Initialize(String itemNo, String trackingMode, String status, BigDecimal initialQty,
                             String reasonCode, String reasonDetail) {}
    public record Policy(String trackingMode, String status, Long version, String reasonCode, String reasonDetail) {}
    public record Adjust(BigDecimal delta, Long version, String reasonCode, String reasonDetail) {}
    public record ItemVO(String itemNo, String itemKind, String skuNo, String name, String baseUnit,
                         String trackingMode, String status, long version,Integer weighed) {}
    public record BalanceVO(String balanceNo, String itemNo, String baseUnit, String trackingMode,
                            String status, BigDecimal onHand, BigDecimal reservedQty,
                            BigDecimal availableQty, long version, String commandNo) {}
    public record MovementVO(String movementNo, String commandNo, String itemNo, String reservationNo,
                             String action, BigDecimal quantity, String baseUnit, BigDecimal onHandBefore,
                             BigDecimal onHandAfter, BigDecimal reservedBefore, BigDecimal reservedAfter,
                             String reasonCode, String reasonDetail, String actorNo, LocalDateTime occurredAt) {}
}
