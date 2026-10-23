package ai.neargo.shop.food.dto;

/** Stable creation receipt is replayed without allocating a second configuration. */
public record CreateOptionGroupResultVO(String configNo, long version, String status) { }
