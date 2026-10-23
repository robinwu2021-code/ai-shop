package ai.neargo.shop.food.dto;

/** The head revision is the original BaseEntity optimistic version, not a publication receipt. */
public record OptionGroupMutationVO(String configNo, long version) { }
