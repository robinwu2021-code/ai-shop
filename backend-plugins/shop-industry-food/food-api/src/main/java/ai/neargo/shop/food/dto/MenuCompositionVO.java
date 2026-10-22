package ai.neargo.shop.food.dto;

import java.util.List;

/** Missing whole-object means legacy unknown; a present empty list is explicitly cleared. */
public record MenuCompositionVO(List<MenuCategoryInput> categories) {
    public MenuCompositionVO { categories = categories == null ? null : List.copyOf(categories); }
}
