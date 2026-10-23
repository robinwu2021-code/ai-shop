package ai.neargo.shop.food.dto;

/** Shared product-level configuration; the selected store grants access, not a second dish identity. */
public record DishVO(String goodsNo, String dishKind, int prepSeconds, DishPresentation presentation,
        MeasurementPolicy measurement, long configVersion, long version) { }
