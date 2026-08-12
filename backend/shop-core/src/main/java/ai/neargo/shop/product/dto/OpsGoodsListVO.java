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
                             String status, List<OpsSkuVO> skus) {

    public record TitleVO(String zh, String en, String ar) {
    }

    /**
     * @param prices 按市场分别定价，{@code market -> 价格（分）}。
     *               {@code prd_sku} 一个逻辑 SKU 在库里是"一市场一行"
     *               （唯一键 {@code entity_no,sku_no,market}），这里按 skuNo 分组聚合。
     */
    public record OpsSkuVO(String skuNo, List<String> optionValues, String spec,
                           Map<String, Long> prices, int stock) {
    }
}
