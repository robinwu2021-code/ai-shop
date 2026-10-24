package ai.neargo.shop.food.dto;

import ai.neargo.shop.spi.product.CatalogRevisionDeserializer;
import ai.neargo.shop.spi.product.DishOfferFacts;
import com.fasterxml.jackson.annotation.JsonAnySetter;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import tools.jackson.databind.annotation.JsonDeserialize;

/** Business responsibility only; original order amounts are never current outstanding balance or payment authority. */
public final class BusinessShareContracts {
    private BusinessShareContracts() { }
    public record Person(@NotBlank @Size(max=32) String personNo,
            @JsonDeserialize(using=DishOfferFacts.MinorDeserializer.class) Long amountMinor) {
        @JsonAnySetter public void unknown(String name,Object value) { throw new IllegalArgumentException(name); }
    }
    public record LineWeight(@NotBlank @Size(max=32) String foodLineNo,@NotBlank @Size(max=32) String personNo,
            @JsonDeserialize(using=CatalogRevisionDeserializer.class) @NotNull Long weight) {
        @JsonAnySetter public void unknown(String name,Object value) { throw new IllegalArgumentException(name); }
    }
    public record CreateReq(@JsonDeserialize(using=CatalogRevisionDeserializer.class) @NotNull Long checkVersion,
            @NotBlank String mode,@NotNull @Size(min=1,max=50) List<@Valid Person> people,
            @Size(max=1000) List<@Valid LineWeight> lineWeights,@NotBlank @Size(max=255) String reason) {
        public CreateReq {
            if (people!=null) people=List.copyOf(people);
            if (lineWeights!=null) lineWeights=List.copyOf(lineWeights);
        }
        @JsonAnySetter public void unknown(String name,Object value) { throw new IllegalArgumentException(name); }
    }
    public record ConfirmReq(@JsonDeserialize(using=CatalogRevisionDeserializer.class) @NotNull Long version,
            @NotBlank @Size(min=64,max=64) String sourceHash,@NotBlank @Size(min=64,max=64) String planHash) {
        @JsonAnySetter public void unknown(String name,Object value) { throw new IllegalArgumentException(name); }
    }
    public record CancelReq(@JsonDeserialize(using=CatalogRevisionDeserializer.class) @NotNull Long version,
            @NotBlank @Size(max=255) String reason) {
        @JsonAnySetter public void unknown(String name,Object value) { throw new IllegalArgumentException(name); }
    }
    public record MembershipScope(String membershipNo,String checkNo,String batchNo,Long version,
            LocalDateTime attachedAt,String operationNo) { }
    public record BatchSource(String batchNo,String originalCheckNo,Long version,String bindingNo,Long bindingVersion,
            String orderNo,String storeOrderNo,String frozenRequestHash,List<MembershipScope> memberships) { }
    public record AllocationSource(String allocationNo,String batchNo,String foodLineNo,String sourceLineRef,
            String sourceComponentRef,Long orderItemId,BigDecimal originalQty,String unit,String coreSnapshotHash,
            Long allocationVersion,Long lineVersion,Long selectionVersion,String lineSnapshotHash,
            String currency,long originalAmountMinor) { }
    public record SourceSnapshot(String entityNo,String storeNo,String checkNo,Long checkVersion,String currency,
            long originalAmountMinor,List<BatchSource> batches,List<AllocationSource> allocations) { }
    public record ShareCell(String allocationNo,String foodLineNo,Long orderItemId,String personNo,long amountMinor) { }
    public record PersonTotal(String personNo,long amountMinor) { }
    public record Division(List<PersonTotal> people,List<ShareCell> cells) {
        public Division { people=List.copyOf(people);cells=List.copyOf(cells); }
    }
    public record PlanVO(String planNo,String checkNo,long version,String status,String storedStatus,String mode,
            String currency,long originalAmountMinor,String sourceHash,String planHash,boolean localSourcesCurrent,
            boolean settlementEligible,boolean financialAuthorityPending,SourceSnapshot source,Division division,
            CreateReq definition,String reason,String createdBy,String confirmedBy,String cancelledBy) { }
}
