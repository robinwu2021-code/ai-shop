package ai.neargo.shop.food.dto;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Collections;
import java.util.LinkedHashMap;

/** Explicit typed group view preserves the existing Config envelope while exposing real draft children. */
public record OptionGroupConfigVO(String configNo, String kind, long revision, String status, Data data) {
    /** Current editable definition, with publication reference kept separate from its draft revision. */
    public record Data(String name, BigDecimal minQty, BigDecimal maxQty, String publishedRevisionNo,
            Map<String, Object> selectionRules, List<Item> items) {
        public Data { selectionRules=Collections.unmodifiableMap(new LinkedHashMap<>(selectionRules)); items=List.copyOf(items); }
    }
    /** Stores a real option reference and quantities, never a computed price or stock value. */
    public record Item(String optionItemNo, String sourceKind, String specValueNo, String modifierNo,
            BigDecimal minQty, BigDecimal maxQty, BigDecimal defaultQty, int sortNo) { }
}
