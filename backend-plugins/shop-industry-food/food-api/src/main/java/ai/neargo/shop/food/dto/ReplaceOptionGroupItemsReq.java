package ai.neargo.shop.food.dto;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;
import java.util.List;

/** Replaces a draft's selection definition and the authorized current store's product bindings. */
public record ReplaceOptionGroupItemsReq(@NotNull @Min(0) Long version,
        @NotNull @Size(max=100) List<@Valid OptionItemInput> items,
        @NotNull @Size(max=100) List<String> goodsNos, @NotNull @Valid SelectionRules selectionRules) {
    public ReplaceOptionGroupItemsReq {
        if (items != null) items = List.copyOf(items);
        if (goodsNos != null) goodsNos = List.copyOf(goodsNos);
    }
    @JsonAnySetter
    public void rejectUnknown(String name, Object value) {
        throw new IllegalArgumentException("Unknown definition field");
    }
}
