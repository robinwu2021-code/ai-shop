package ai.neargo.shop.product.service;

import ai.neargo.shop.common.PageData;
import ai.neargo.shop.product.dto.GoodsVO;

/**
 * 商品收藏（TDD-C端商品收藏与送达判断）。当前登录的买家 × 商品。
 */
public interface GoodsFavoriteService {

    /** 收藏 / 取消。返回操作之后的状态：true = 现在是收藏着的 */
    boolean toggle(String goodsNo);

    /** 当前买家收藏了这件没有。未登录 = false（详情页游客可看，不能因此报错） */
    boolean isFavorited(String goodsNo);

    /**
     * 我的收藏 · 商品，收藏时间倒序。
     *
     * <p><b>下架的也返回</b>（{@code onSale=false}）：他收藏它是有原因的，
     * 自动消失会让人以为数据丢了 —— 端上压淡显示，由他自己删。
     * 商品本身被删掉（查不到）的才跳过。
     */
    PageData<GoodsVO> page(long page, long size);
}
