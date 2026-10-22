package ai.neargo.shop.food.dto;

/** Optimistic revision receipt; it is not a publication. */
public record ModifierMutationVO(String configNo, long version) { }
