package ai.neargo.shop.food.dto;

/** Business identity and draft state, without a persistence entity or physical id. */
public record CreateModifierResultVO(String configNo, long version, String status) { }
