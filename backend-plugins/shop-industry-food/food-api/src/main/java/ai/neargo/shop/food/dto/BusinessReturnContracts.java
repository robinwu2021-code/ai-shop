package ai.neargo.shop.food.dto;

import ai.neargo.shop.food.policy.SalesRestrictionPolicy;
import ai.neargo.shop.spi.product.CatalogRevisionDeserializer;
import ai.neargo.shop.spi.trade.IndustryOrderPort.Result;

import com.fasterxml.jackson.annotation.JsonAnySetter;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import tools.jackson.databind.annotation.JsonDeserialize;

import java.math.BigDecimal;
import java.util.List;

/**
 * Independent employee kitchen requests never invoke a financial after-sale or reduce original
 * payable amounts.
 */
public final class BusinessReturnContracts {
    public static final String KIND = "BUSINESS_RETURN_V1";

    private BusinessReturnContracts() {}

    public record Line(
            @NotBlank @Size(max = 32) String foodLineNo,
            @JsonDeserialize(using = SalesRestrictionPolicy.DecimalDeserializer.class) @NotNull
                    BigDecimal qty,
            String componentNo) {
        public Line(String foodLineNo, BigDecimal qty) {
            this(foodLineNo, qty, null);
        }

        @JsonAnySetter
        public void unknown(String name, Object value) {
            throw new IllegalArgumentException(name);
        }
    }

    public record CreateReq(
            @JsonDeserialize(using = CatalogRevisionDeserializer.class) @NotNull Long batchVersion,
            @NotNull @Size(min = 1, max = 256) List<@Valid Line> lines,
            @NotBlank @Size(max = 255) String reason) {
        public CreateReq {
            if (lines != null) lines = List.copyOf(lines);
        }

        @JsonAnySetter
        public void unknown(String name, Object value) {
            throw new IllegalArgumentException(name);
        }
    }

    public record CancelReq(
            @JsonDeserialize(using = CatalogRevisionDeserializer.class) @NotNull Long version,
            @NotBlank @Size(max = 255) String reason) {
        @JsonAnySetter
        public void unknown(String name, Object value) {
            throw new IllegalArgumentException(name);
        }
    }

    public record ComponentSource(
            String sourceComponentRef,
            String kind,
            String allocationNo,
            Long orderItemId,
            BigDecimal originalQty,
            String unit,
            String coreSnapshotHash,
            Long allocationVersion,
            String componentNo,
            Long componentVersion) {}

    public record SourceItem(
            String foodLineNo,
            String sourceLineRef,
            String mainAllocationNo,
            Long mainOrderItemId,
            BigDecimal qty,
            BigDecimal originalQty,
            Long lineVersion,
            List<ComponentSource> components) {
        public SourceItem {
            components = List.copyOf(components);
        }
    }

    /**
     * Genuine original-owner read is evidence at checkedAt, never a lock on future core payment
     * changes.
     */
    public record OwnerRead(String checkedAt, Result original) {}

    public record Frozen(
            String kind,
            int schema,
            String entityNo,
            String storeNo,
            String batchNo,
            Long batchVersion,
            String cartNo,
            Long cartVersion,
            String bindingNo,
            Long bindingVersion,
            String buyerNo,
            String actorNo,
            String reason,
            String sourceHash,
            OwnerRead ownerRead,
            List<SourceItem> items) {
        public Frozen {
            items = List.copyOf(items);
        }
    }

    public record ItemVO(
            String returnItemNo,
            String foodLineNo,
            String sourceLineRef,
            BigDecimal qty,
            String kitchenDisposition,
            String mainAllocationNo) {}

    public record ReturnVO(
            String returnNo,
            String batchNo,
            String storeNo,
            long version,
            String status,
            String financialStatus,
            String kitchenStatus,
            String coreCaseRef,
            boolean refundRequested,
            boolean originalPayableChanged,
            String reason,
            String actorNo,
            List<ItemVO> items,
            Frozen source) {
        public ReturnVO {
            items = List.copyOf(items);
        }
    }
}
