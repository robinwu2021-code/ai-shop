package ai.neargo.shop.food.dto;

/** Additive schedule remains unknown when reading a legacy provider without this field. */
public record MenuConfigResultVO(MenuVO config, MenuScheduleVO schedule) {
    public MenuConfigResultVO(MenuVO config) { this(config, null); }
}
