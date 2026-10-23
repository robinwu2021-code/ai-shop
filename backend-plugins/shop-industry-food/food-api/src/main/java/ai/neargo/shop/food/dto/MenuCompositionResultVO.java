package ai.neargo.shop.food.dto;

/** Frozen idempotency receipt for mutable composition edits, not a published sales menu. */
public record MenuCompositionResultVO(MenuVO menu, MenuCompositionVO composition) { }
