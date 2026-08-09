package ai.neargo.shop.merchant.service;

/**
 * 店铺码：印在物料上、扫了能直达这家店的短码（C-ST-10 / B-11.2.6）。
 *
 * <p>这两个方法原本长在 user 域的 {@code StoreFavoriteService} 上。放错位置的代价很具体：
 * {@code ensureStoreCode} 会**写 mch_entity**，也就是用户域在改商家的行；
 * 而它被放在那里的唯一理由，只是「扫码进来之后顺手要看收藏状态」——
 * 那是调用顺序上的相邻，不是职责上的同类。
 */
public interface StoreCodeService {

    /** 店铺码 → merchantNo。码不存在给 404，不静默回退到首页。 */
    String resolve(String storeCode);

    /** 商家自己的店铺码；没有就生成一个。 */
    String ensureFor(String merchantNo);
}
