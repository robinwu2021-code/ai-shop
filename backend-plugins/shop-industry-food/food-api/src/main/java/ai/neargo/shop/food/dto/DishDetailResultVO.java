package ai.neargo.shop.food.dto;

import ai.neargo.shop.spi.product.GoodsCatalogQueryPort.GoodsFact;
import java.util.List;

/** Current management definitions; option groups are not a sellability/publication receipt. */
public record DishDetailResultVO(DishVO dish, List<OptionGroupConfigVO> optionGroups, String coreCatalogRef,
        GoodsFact coreCatalog) {
    public DishDetailResultVO { optionGroups = List.copyOf(optionGroups); }
}
