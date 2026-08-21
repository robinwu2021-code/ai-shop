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

    /**
     * 店铺的小程序码（PNG base64，不含 {@code data:} 前缀）。
     *
     * <p><b>生成一次就落库复用</b>：{@code wxacode.getUnlimited} 是永久码且每个 appid
     * 总量有限（十万级）—— 每次请求都现调，几百个商家反复刷新页面就能把额度耗掉，
     * 而额度用尽之后新入驻的商家<b>再也拿不到码</b>。
     *
     * @return 通道未开启或生成失败时 <b>null</b> —— 端上据此不显示码，
     *     而不是显示一张永远加载不出来的图
     */
    String acodeBase64(String merchantNo);
}
