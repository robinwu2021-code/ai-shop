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

    @Override
    public void resyncPools(String entityNo) {
        /*
         * **吞掉异常**，见接口注释：审核通过与范围保存都已经成功了，
         * 让池重建把它们回滚掉是更坏的结果 —— 商家会看到「审核失败」，
         * 而审核其实过了。可见性晚一步，下次上下架会自愈。
         */
        try {
            int n = goodsService.resyncCommunityPools(entityNo);
            log.info("[pool] 主体 {} 可达范围变化，重建社区池：{} 件商品", entityNo, n);
        } catch (RuntimeException e) {
            log.error("[pool] 主体 {} 社区池重建失败 —— 这批货可能仍对买家不可见，"
                    + "商家任意一次上下架会自愈", entityNo, e);
        }
    }
}
