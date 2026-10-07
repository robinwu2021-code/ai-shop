package ai.neargo.shop.product.port;

import ai.neargo.shop.product.service.MerchantGoodsService;
import ai.neargo.shop.spi.product.StoreShelfPort;
import org.springframework.stereotype.Component;

/**
 * 门店货架平台开关的落地：薄转调 {@link MerchantGoodsService}。
 * 压/放货架要走 setStoreOnSale → 总闸重算 → syncPool 那条**已有**链路，
 * 逻辑都在商品服务里 —— Port 只负责让 merchant 域够得着。
 */
@Component
public class StoreShelfPortImpl implements StoreShelfPort {

    private static final org.slf4j.Logger log =
            org.slf4j.LoggerFactory.getLogger(StoreShelfPortImpl.class);

    private final MerchantGoodsService goodsService;

    public StoreShelfPortImpl(MerchantGoodsService goodsService) {
        this.goodsService = goodsService;
    }

    @Override
    public void platformOffline(String entityNo, String storeNo) {
        goodsService.platformOfflineStore(entityNo, storeNo);
    }

    @Override
    public void platformRestore(String entityNo, String storeNo) {
        goodsService.platformRestoreStore(entityNo, storeNo);
    }

    /**
     * <b>同步跑，不挪到后台。</b>
     *
     * <p>2026-10-07 一度改成后台线程（当时这一步要 24~27 秒，报障「切换商家配送，反应很慢」），
     * 被两条场景测试当场拦下，而它们是对的：
     * <ul>
     *   <li>{@code StoreScopedVisibilityFlowTest.suspendingAStoreWithdrawsItsGoodsFromTheCommunityPool}</li>
     *   <li>{@code QuickStartFlowTest.goodsListedBeforeLicenseBecomeVisibleOnApproval}</li>
     * </ul>
     *
     * <p><b>两个方向的代价不对称</b>：放宽可见性（审核通过、开一路）晚一步只是货晚几秒出现；
     * 而<b>收窄</b>（停用门店、关一路、缩小范围）晚一步，买家还搜得到、还点得进去 ——
     * 下单那道闸是实时算的会拦住他，于是变成「看得见、下不了单」。
     * 停用门店这条正是出过事故、才立起那条测试的。
     *
     * <p>所以慢要在<b>慢的地方</b>修（见 {@code resyncCommunityPools}：整趟只算一次可达社区、
     * 池行一次读完），而不是把它挪到看不见的地方。耗时打进日志，下次再慢有数可查。
     */
    @Override
    public void resyncPools(String entityNo) {
        long t0 = System.currentTimeMillis();
        /*
         * **吞掉异常**，见接口注释：审核通过与范围保存都已经成功了，
         * 让池重建把它们回滚掉是更坏的结果 —— 商家会看到「审核失败」，
         * 而审核其实过了。可见性晚一步，下次上下架会自愈。
         */
        try {
            int n = goodsService.resyncCommunityPools(entityNo);
            log.info("[pool] 主体 {} 可达范围变化，重建社区池：{} 件商品，用时 {} ms",
                    entityNo, n, System.currentTimeMillis() - t0);
        } catch (RuntimeException e) {
            log.error("[pool] 主体 {} 社区池重建失败 —— 这批货可能仍对买家不可见，"
                    + "商家任意一次上下架会自愈", entityNo, e);
        }
    }
}