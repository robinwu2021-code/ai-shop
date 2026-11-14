package ai.neargo.shop.food.dto;

import ai.neargo.shop.spi.product.CatalogRevisionDeserializer;
import com.fasterxml.jackson.annotation.JsonAnySetter;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.util.List;
import tools.jackson.databind.annotation.JsonDeserialize;

/** Explicit kitchen disposition of original return quantities, independent of financial case execution. */
public final class KitchenReturnContracts {
    private KitchenReturnContracts() { }
    public record ItemDecision(@NotBlank String returnItemNo,@NotBlank String disposition) {
        @JsonAnySetter public void unknown(String name,Object value) { throw new IllegalArgumentException(name); }
    }
    public record HandleReq(@JsonDeserialize(using=CatalogRevisionDeserializer.class) @NotNull Long version,
            @NotNull @Size(min=1,max=1000) List<@Valid ItemDecision> decisions,
            @NotBlank @Size(max=255) String reason) {
        public HandleReq { if (decisions!=null) decisions=List.copyOf(decisions); }
        @JsonAnySetter public void unknown(String name,Object value) { throw new IllegalArgumentException(name); }
    }
    public record ItemVO(String returnItemNo,String foodLineNo,String componentNo,BigDecimal qty,
            String disposition) { }
    public record ReturnKitchenVO(String returnNo,String batchNo,String kitchenStatus,long version,List<ItemVO> items) { }
    public record HandledVO(String operationNo,String returnNo,String kitchenStatus) { }
}
