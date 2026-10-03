package ai.neargo.shop.product.service;

import java.util.List;
import java.util.Optional;

/**
 * 给 AI 经营助手（soukmind）的商品取数 —— {@code /internal/ai/v1/goods/**} 的实现。
 *
 * <p>一律只看<b>本商户</b>（{@code entity_no}）的商品；他人商户的商品号 / SKU 号当作不存在（{@link Optional#empty()}），
 * 不区分「不存在」与「不是你的」—— 后者会泄露别家有这个号。
 *
 * <p>库存按门店口径：某 SKU 只要有任意一条 {@code prd_store_stock} 行就整体转为店级管理，没有行的店视为 0
 * （与 {@code PrdStoreStock} 的模型一致）；没传门店时用 SKU 总量。可售 = 库存 − 锁定。
 * 金额一律为分。设计：{@code docs/technical/TDD-AI取数接口.md}。
 */
public interface AiGoodsService {

    /** 在售商品按累计销量排行。 */
    List<GoodsRow> cumulativeRanking(String merchantNo, String storeNo, boolean ascending, int limit);

    /** 商品列表（全部状态）。{@code categoryNo}/{@code keyword} 可空；页码从 1 起。 */
    Page list(String merchantNo, String storeNo, String categoryNo, String keyword, int pageNum, int pageSize);

    Optional<Detail> detail(String merchantNo, String storeNo, String goodsNo);

    Optional<SkuRow> sku(String merchantNo, String storeNo, String skuNo);

    /** 可售库存 ≤ 阈值的 SKU，按库存升序。只看在售商品。 */
    List<SkuRow> lowStock(String merchantNo, String storeNo, int threshold, int limit);

    record GoodsRow(String goodsNo, String title, String cover, long minPrice, long maxPrice,
                    long totalStock, long totalSales, boolean onSale) {
    }

    record Page(List<GoodsRow> items, long total) {
    }

    record Detail(GoodsRow goods, String categoryNo, String subtitle, List<SkuRow> skus) {
    }

    record SkuRow(String skuNo, String goodsNo, String title, String spec, long price, Long costPrice,
                  long stock, boolean onSale) {
    }
}
