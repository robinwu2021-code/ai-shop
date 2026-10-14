package ai.neargo.shop.product.service;

import ai.neargo.shop.product.dto.FrequentItemVO;
import ai.neargo.shop.product.dto.RebuyResultVO;
import ai.neargo.shop.product.dto.ReorderResultVO;
import ai.neargo.shop.product.dto.StoreHomeVO;

import java.util.List;

/** 门店主页（[API 清单 §2.10]）。主页与商品列表游客可访问，常买清单需要登录。 */
public interface StoreService {

    StoreHomeVO home(String merchantNo, String userNo, boolean favorited);

    /**
     * 门店门户（TDD-C端门店化与门店门户 AC9）：<b>以门店为根</b> —— 门头是门店名、门面文案取这家店、
     * 商品只列本店在售的、货架取本店的。暂停营业的店照样回（{@code closed=true}），并给同主体最近的营业店。
     *
     * @param latE6 买家位置，可空；有就算出到这家店的距离
     */
    StoreHomeVO homeOfStore(ai.neargo.shop.spi.user.StoreDirectoryPort.StoreCard store, String userNo,
                            boolean favorited, Integer latE6, Integer lngE6);

    /** 门户的商品列表：本店在售，可按货架类目与关键词筛 */
    ai.neargo.shop.common.PageData<ai.neargo.shop.product.dto.GoodsVO> goodsOfStore(
            ai.neargo.shop.spi.user.StoreDirectoryPort.StoreCard store, String categoryNo, String keyword,
            long page, long size);

    /** 我在这家店的常买清单（C-ST-02），按购买次数倒序。 */
    List<FrequentItemVO> frequentItems(String merchantNo);

    /** 一键再来一单（C-ST-03）。**失效品与缺货品显式回报**，不悄悄少加。 */
    RebuyResultVO rebuy(String merchantNo);

    /**
     * 一键再来一单：<b>整单</b>复制到购物车（C-ST-03）。
     *
     * <p>与 {@link #rebuy} 是两件事，别合并：{@code rebuy} 复制的是「这家店我常买的」，
     * 这里复制的是「这一单买过的」。用户在订单页点的是后者。
     */
    ReorderResultVO reorderFrom(String orderNo);
}
