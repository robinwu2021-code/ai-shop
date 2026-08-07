package ai.neargo.shop.user.service;

import ai.neargo.shop.user.dto.MerchantVO;

import java.util.List;

/** 收藏的店与店铺码解析（C-ST-07/08/10）。 */
public interface StoreFavoriteService {

    /** 「我的常去店」= 收藏 + 归因命中的店，去重后按最近优先。 */
    List<MerchantVO.Brief> myStores();

    /** 收藏/取消，返回最新列表。 */
    List<MerchantVO.Brief> toggle(String merchantNo);

    boolean isFavorited(String merchantNo);

    /** 店铺码 → merchantNo。码不存在给 404，不静默回退到首页。 */
    String resolveStoreCode(String storeCode);

    /** 商家自己的店铺码；没有就生成一个（B-11.2.6）。 */
    String ensureStoreCode(String merchantNo);
}
