package ai.neargo.shop.food.dto;

import ai.neargo.shop.food.policy.SalesRestrictionPolicy;
import ai.neargo.shop.spi.inventory.MaterialCatalogFacts;
import com.fasterxml.jackson.annotation.JsonAnySetter;
import java.math.BigDecimal;
import java.util.List;
import tools.jackson.databind.annotation.JsonDeserialize;

/** Selected original MAIN whole-unit quantities only; no amount, buyer, allocation or kitchen command is accepted. */
public record DiningReturnApplyReq(
        @JsonDeserialize(using = MaterialCatalogFacts.TextDeserializer.class) String batchNo,
        @JsonDeserialize(using = MaterialCatalogFacts.TextDeserializer.class) String type,
        @JsonDeserialize(using = MaterialCatalogFacts.TextDeserializer.class) String reason,
        @JsonDeserialize(using = MaterialCatalogFacts.TextDeserializer.class) String reasonCode,
        @JsonDeserialize(contentUsing = MaterialCatalogFacts.TextDeserializer.class) List<String> images,
        List<Line> lines) {
    public DiningReturnApplyReq {
        images = images == null ? List.of() : List.copyOf(images);
        lines = lines == null ? List.of() : List.copyOf(lines);
    }
    @JsonAnySetter public void unknown(String name, Object value) {
        throw new IllegalArgumentException("Unknown dining return field");
    }
    public record Line(
            @JsonDeserialize(using = MaterialCatalogFacts.TextDeserializer.class) String sourceLineRef,
            @JsonDeserialize(using = SalesRestrictionPolicy.DecimalDeserializer.class) BigDecimal quantity) {
        @JsonAnySetter public void unknown(String name, Object value) {
            throw new IllegalArgumentException("Unknown dining return source field");
        }
    }
}
