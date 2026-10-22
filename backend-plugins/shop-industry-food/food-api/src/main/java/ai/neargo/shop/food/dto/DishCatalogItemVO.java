package ai.neargo.shop.food.dto;

import ai.neargo.shop.spi.product.GoodsCatalogQueryPort.GoodsSummary;

/** Joins verified product display facts to restaurant attributes without copying product authority. */
public record DishCatalogItemVO(DishVO dish, GoodsSummary product) { }
