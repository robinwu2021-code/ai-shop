package ai.neargo.shop.product.dto;

import java.util.List;
import java.util.Map;

/**
 * 运营端「商品池」列表行（goods 粒度，SKU 收在 {@link #skus()} 里）。
 *
 * <p>与 {@link GoodsVO} 分开成一个新类型，而不是给它加字段：{@code GoodsVO}
 * 的字段与顺序是<b>对齐 c-app 消费端契约</b>的（见其类注释），运营端要的
 * 多市场价格表、三语标题原文，C 端从不需要——加进去只会让消费端多背几十字节，
 * 且以后改 c-app 契约时要多想一层"运营端要不要也跟着变"。
 *
 * @param title      三语标题（{@code prd_goods.title_i18n}，此前 GoodsVO 没往外传）
 * @param categoryName 类目名快照，查询时批量拼上——{@code prd_goods} 本身只存 categoryNo
 */
public record OpsGoodsListVO(String goodsNo, TitleVO title, String cover,
                             String merchantNo, String merchantName,
                             String categoryNo, String categoryName,
                             String status, List<OpsSkuVO> skus,
                             /**
                              * 门店投影（查询带 {@code storeNo} 时才有值）：这件商品在**那家店**
                              * 上不上架。{@code null} = 未按店管理（跟随主体级 status）。
                              * 语义与 {@code prd_store_goods} 一致：有任意店级行即按店管理，
                              * 没有行的店视为未上架。
                              */
                             Boolean storeOnSale) {

    public record TitleVO(String zh, String en, String ar) {
    }

    /**
     * @param prices 按市场分别定价，{@code market -> 价格（分）}。
     *               {@code prd_sku} 一个逻辑 SKU 在库里是"一市场一行"
     *               （唯一键 {@code entity_no,sku_no,market}），这里按 skuNo 分组聚合。
     * @param storeStock 门店投影（查询带 {@code storeNo} 时才有值）：该店的可用库存。
     *                   {@code null} = 该 SKU 未启用分店库存（stock 就是它的数）；
     *                   启用了但该店没有行 = 0（不是回退总量，与 V13 语义一致）。
     */
    public record OpsSkuVO(String skuNo, List<String> optionValues, String spec,
                           Map<String, Long> prices, int stock, Integer storeStock) {
    }
}
