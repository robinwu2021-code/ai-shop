package ai.neargo.shop.user.service;

import ai.neargo.shop.user.dto.StoreBriefVO;

import java.util.List;

/**
 * 收藏的店（C-ST-07/08）。
 *
 * <p>店铺码的解析与生成已迁往 merchant 域的 {@code StoreCodeService}——
 * 那两个方法会读写 {@code mch_entity}，本不该由用户域承担。
 */
public interface StoreFavoriteService {

    /** 「我的常去店」= 收藏 + 归因命中的店，去重后按最近优先。 */
    List<StoreBriefVO> myStores();

    /** 收藏/取消，返回最新列表。 */
    List<StoreBriefVO> toggle(String merchantNo);

    boolean isFavorited(String merchantNo);
}
