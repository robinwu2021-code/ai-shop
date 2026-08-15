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
}
