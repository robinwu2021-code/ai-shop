package ai.neargo.shop.food.dto;

import ai.neargo.shop.food.dto.PublicationSnapshot.Dependency;

/** The actual root product binding and complete immutable group travel with the recipe history. */
public record RecipeOptionGroupSnapshot(String goodsNo, Dependency publication, PublicationSnapshot definition) { }
