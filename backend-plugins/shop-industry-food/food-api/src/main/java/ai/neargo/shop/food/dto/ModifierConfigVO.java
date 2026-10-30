package ai.neargo.shop.food.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.math.BigDecimal;
import java.util.List;

/** Current entity-shared management definition, not a store sale catalog or frozen revision. */
public record ModifierConfigVO(String configNo, String kind, long version, String status, Data data) {
    public record Data(String name, String salesSkuNo, String publishedRevisionNo, List<Component> components,
            @JsonInclude(JsonInclude.Include.NON_NULL) String inventoryMode) {
        public Data { components = List.copyOf(components); }
        /** Preserve the exact serialized shape and constructor of historical definitions. */
        public Data(String name, String salesSkuNo, String publishedRevisionNo, List<Component> components) {
            this(name, salesSkuNo, publishedRevisionNo, components, null);
        }
    }
    public record Component(String componentNo, String memberNo, BigDecimal quantity) { }
}
