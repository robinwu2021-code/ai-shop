package ai.neargo.shop.food.dto;

import ai.neargo.shop.food.policy.SalesRestrictionPolicy;
import ai.neargo.shop.spi.inventory.MaterialCatalogFacts;
import ai.neargo.shop.spi.product.CatalogRevisionDeserializer;
import com.fasterxml.jackson.annotation.JsonAnySetter;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.util.List;
import tools.jackson.databind.annotation.JsonDeserialize;

/** Commands carry deltas; accepted order quantities and frozen routing remain server authority. */
public final class KitchenContracts {
    private KitchenContracts() { }
    public record FrozenRoute(String skuNo, String stationNo, String taskRole, long routeVersion,
            String revisionNo, String contentHash) { }
    public record FrozenRoutes(List<FrozenRoute> routes) {
        public FrozenRoutes { routes = List.copyOf(routes); }
    }
    public record TaskActionReq(@JsonDeserialize(using=CatalogRevisionDeserializer.class) @NotNull Long version,
            @JsonDeserialize(using=MaterialCatalogFacts.TextDeserializer.class)
            @NotBlank @Size(max=24) String action,
            @JsonDeserialize(using=SalesRestrictionPolicy.DecimalDeserializer.class) @NotNull BigDecimal qty,
            @Size(max=255) String reason) {
        @JsonAnySetter public void unknown(String name,Object value) { throw new IllegalArgumentException(name); }
    }
    public record TaskDeltaReq(@JsonDeserialize(using=CatalogRevisionDeserializer.class) @NotNull Long version,
            @JsonDeserialize(using=SalesRestrictionPolicy.DecimalDeserializer.class) @NotNull BigDecimal qty,
            @Size(max=255) String reason) {
        @JsonAnySetter public void unknown(String name,Object value) { throw new IllegalArgumentException(name); }
    }
    public record RemakeReq(@JsonDeserialize(using=CatalogRevisionDeserializer.class) @NotNull Long version,
            @JsonDeserialize(using=SalesRestrictionPolicy.DecimalDeserializer.class) @NotNull BigDecimal qty,
            @NotBlank @Size(max=255) String reason) {
        @JsonAnySetter public void unknown(String name,Object value) { throw new IllegalArgumentException(name); }
    }
    public record HandoverLine(@NotBlank String foodLineNo,
            @JsonDeserialize(using=SalesRestrictionPolicy.DecimalDeserializer.class) @NotNull BigDecimal qty) { }
    public record HandoverReq(@NotBlank String kind, @NotNull List<HandoverLine> lines,
            @NotBlank @Size(max=255) String reason) {
        public HandoverReq { if (lines!=null) lines=List.copyOf(lines); }
        @JsonAnySetter public void unknown(String name,Object value) { throw new IllegalArgumentException(name); }
    }
    public record HandoverVO(String operationNo,String batchNo,String status,boolean deliveryPending,
            String coreDeliveryRef) { }
    public record CourseReq(@NotBlank @Size(max=64) String name, @NotNull Integer sequenceNo) { }
    public record CourseActionReq(@JsonDeserialize(using=CatalogRevisionDeserializer.class) @NotNull Long version,
            @NotBlank String action,@Size(max=255) String reason,List<String> seatNos) {
        public CourseActionReq { if (seatNos!=null) seatNos=List.copyOf(seatNos); }
    }
    public record CourseFireReq(@JsonDeserialize(using=CatalogRevisionDeserializer.class) @NotNull Long version,
            @NotNull @Size(max=100) List<@NotBlank String> seatNos) { }
    public record CourseVO(String courseNo, String checkNo, String name, int sequenceNo, String status,long version) { }
    public record TaskVO(String taskNo, String ticketNo, String batchNo, String foodLineNo, String componentNo,
            String stationNo, String taskRole, long routeVersion, BigDecimal qty, BigDecimal makingQty,
            BigDecimal readyQty, BigDecimal servedQty, BigDecimal voidQty, String status,
            String seatNo, String courseNo, long version, String ticketKind, String originalTicketNo) { }
    public record EventVO(String eventNo, String taskNo, String action, BigDecimal qty, String reason,
            String inventoryDocNo) { }
    public record TicketVO(String ticketNo, String batchNo, String kind, String originalTicketNo,
            String status, List<TaskVO> tasks) { }
}
