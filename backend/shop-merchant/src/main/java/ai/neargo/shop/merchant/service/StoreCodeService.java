package ai.neargo.shop.merchant.service;

/**
 * 店铺码：印在物料上、扫了能直达这家店的短码（C-ST-10 / B-11.2.6）。
 *
 * <p>这两个方法原本长在 user 域的 {@code StoreFavoriteService} 上。放错位置的代价很具体：
 * {@code ensureStoreCode} 会**写 mch_entity**，也就是用户域在改商家的行；
 * 而它被放在那里的唯一理由，只是「扫码进来之后顺手要看收藏状态」——
 * 那是调用顺序上的相邻，不是职责上的同类。
 *
 * <p><b>V298 起码的粒度是门店</b>（{@code mch_store.store_code}）。此前一主体一码，
 * 多门店商家每家分店贴的是同一个码，扫码/进店/注册在分店之间分不开。
 * 旧码没有作废：默认店继承了主体上那个码，所以已经印出去的贴纸照常扫得进来。
 */
public interface StoreCodeService {

    /**
     * 码指向的那家店。
     *
     * @param storeCode 印在物料上的短码
     * @return 主体号 + 门店号；<b>门店号可能为空</b>（主体连门店行都没有的历史数据）。
     *     码不存在给 404，不静默回退到首页 —— 静默回退会让「码印错了」永远没人发现
     */
    CodeTarget resolveTarget(String storeCode);

    /**
     * 码指向的主体。{@link #resolveTarget} 的窄化版本，给只关心主体的调用方。
     */
    String resolve(String storeCode);

    /**
     * 这家<b>门店</b>的店铺码；没有就发一个。
     *
     * <p>{@code storeNo} 为空时退回主体的默认店 —— B 端没切店时问的就是「我这家店的码」。
     */
    String ensureForStore(String merchantNo, String storeNo);

    /** 商家默认店的店铺码；没有就生成一个。 */
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

    /**
     * 码解析结果。
     *
     * @param entityNo 主体号，永不为空
     * @param storeNo  门店号；<b>空 = 这个码只知道是哪个主体</b>，不知道是哪家店。
     *                 拿它当「默认店」用之前先想清楚：分不出店与确定是默认店是两件事
     */
    record CodeTarget(String entityNo, String storeNo) {
    }
}
