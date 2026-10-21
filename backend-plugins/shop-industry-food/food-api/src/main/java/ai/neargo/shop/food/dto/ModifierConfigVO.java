package ai.neargo.shop.food.dto;

import java.math.BigDecimal;
import java.util.List;

/** Current entity-shared management definition, not a store sale catalog or frozen revision. */
public record ModifierConfigVO(String configNo, String kind, long version, String status, Data data) {
    public record Data(String name, String salesSkuNo, String publishedRevisionNo, List<Component> components) {
        public Data { components = List.copyOf(components); }
    }
    public record Component(String componentNo, String memberNo, BigDecimal quantity) { }
}
