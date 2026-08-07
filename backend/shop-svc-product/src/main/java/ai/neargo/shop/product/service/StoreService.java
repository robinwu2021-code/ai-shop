package ai.neargo.shop.product.service;

import ai.neargo.shop.product.dto.FrequentItemVO;
import ai.neargo.shop.product.dto.RebuyResultVO;
import ai.neargo.shop.product.dto.StoreHomeVO;

import java.util.List;

/** 门店主页（[API 清单 §2.10]）。主页与商品列表游客可访问，常买清单需要登录。 */
public interface StoreService {

    StoreHomeVO home(String merchantNo, String userNo, boolean favorited);

    /** 我在这家店的常买清单（C-ST-02），按购买次数倒序。 */
    List<FrequentItemVO> frequentItems(String merchantNo);

    /** 一键再来一单（C-ST-03）。**失效品与缺货品显式回报**，不悄悄少加。 */
    RebuyResultVO rebuy(String merchantNo);
}
