package ai.neargo.shop.spi.product;

import java.util.List;

/** Platform-owned product/category facts; clients cannot supply earning classifications. */
public interface PointsEarningCatalogPort {
    record Category(String categoryNo, String parentNo, String name, String path) {}
    List<Category> categories();
    String goodsCategory(String goodsNo);
    List<String> ancestry(String categoryNo);
}
