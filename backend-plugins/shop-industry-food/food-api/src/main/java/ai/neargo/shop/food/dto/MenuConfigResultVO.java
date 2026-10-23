package ai.neargo.shop.food.dto;

import java.util.List;

/** Missing additive sections mean unknown on older providers, never a fabricated empty definition. */
public record MenuConfigResultVO(MenuVO config, MenuScheduleVO schedule, MenuCompositionVO composition,
        List<MenuContextInput> contexts) {
    public MenuConfigResultVO { contexts = contexts == null ? null : List.copyOf(contexts); }
    public MenuConfigResultVO(MenuVO config) { this(config, null, null, null); }
    public MenuConfigResultVO(MenuVO config, MenuScheduleVO schedule) { this(config, schedule, null, null); }
    public MenuConfigResultVO(MenuVO config, MenuScheduleVO schedule, MenuCompositionVO composition) {
        this(config, schedule, composition, null);
    }
}
