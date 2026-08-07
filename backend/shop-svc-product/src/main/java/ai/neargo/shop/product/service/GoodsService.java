package ai.neargo.shop.product.service;

import ai.neargo.shop.common.PageData;
import ai.neargo.shop.product.dto.GoodsVO;

/** 商品读（[API 清单 §2.3]）。游客可访问。 */
public interface GoodsService {

    /**
     * 商品列表。同一个端点承担四种场景，靠参数区分：
     * 首页按社区逛（{@code communityNo}）、频道（{@code type}）、搜索（{@code keyword}）、
     * <b>店内搜索（{@code merchantNo}）</b> —— 最后一种是门店主页的店内搜索（C-ST-06），
     * 复用同一端点是刻意的：店内店外必须是同一份数据同一个价。
     */
    PageData<GoodsVO> list(GoodsQuery query);

    GoodsVO detail(String goodsNo);

    /** 规格选中后的实时价格与库存（C-PD-04）。下单前的最后一次校准。 */
    ai.neargo.shop.product.dto.SkuPriceVO skuPrice(String goodsNo, String skuNo);

    /** 搜索联想（C-SR-02）。返回商品标题片段，不是商品本身。 */
    java.util.List<String> suggest(String keyword);

    /** 热搜词（C-SR-04）。 */
    java.util.List<String> hotWords();

    record GoodsQuery(String communityNo, String merchantNo, String type,
                      String categoryNo, String keyword, long page, long size) {
    }
}
