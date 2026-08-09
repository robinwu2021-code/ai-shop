package ai.neargo.shop.product.service;

import ai.neargo.shop.product.dto.FrequentItemVO;
import ai.neargo.shop.product.dto.RebuyResultVO;
import ai.neargo.shop.product.dto.ReorderResultVO;
import ai.neargo.shop.product.dto.StoreHomeVO;

import java.util.List;

/** 门店主页（[API 清单 §2.10]）。主页与商品列表游客可访问，常买清单需要登录。 */
public interface StoreService {

    StoreHomeVO home(String merchantNo, String userNo, boolean favorited);

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
