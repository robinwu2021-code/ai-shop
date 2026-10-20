package ai.neargo.shop.product.dto;

public record CardProductBindingView(String goodsNo, String skuNo, String templateVersionNo, int unitsPerItem,
                                      String currency, String status, long productRevision, long bindingRevision) { }
