package ai.neargo.shop.invbridge.port;

import ai.neargo.shop.inventory.config.ConditionalOnInventory;
import ai.neargo.shop.invbridge.InventoryWritebackService;
import ai.neargo.shop.product.entity.PrdSellRule;
import ai.neargo.shop.product.service.StockSyncService;
import ai.neargo.shop.spi.product.InvManagedPort;
import ai.neargo.shop.spi.product.OnlineQuotaPort;
import ai.neargo.shop.spi.user.MerchantQueryPort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 「改库存」→ 线上额度（TDD-商品纳入进销存开关 §8 / §18.6）。
 *
 * <p>只有<b>同时满足</b>三条才接住：这家店开了库存同步、这件商品接入进销存、进销存里有它的账。
 * 接住之后存的是本店本品的「手动」规则，随即按规则重算一次 —— 于是线上可卖变成
 * {@code min(店主填的数, 可用)}，而实存一个数都不动。
 *
 * <p>三条有一条不满足就返回 {@code false}，调用方照旧直接改商城库存 —— 那是今天的行为，一个字没变。
 */
@Component
@ConditionalOnInventory
public class OnlineQuotaPortImpl implements OnlineQuotaPort {

    private static final Logger log = LoggerFactory.getLogger(OnlineQuotaPortImpl.class);
    private static final String OPERATOR = "STOCK_QUOTA";

    private final StockSyncService stockSync;
    private final InvManagedPort invManaged;
    private final InventoryWritebackService writeback;
    private final MerchantQueryPort merchants;

    public OnlineQuotaPortImpl(StockSyncService stockSync, InvManagedPort invManaged,
                               InventoryWritebackService writeback, MerchantQueryPort merchants) {
        this.stockSync = stockSync;
        this.invManaged = invManaged;
        this.writeback = writeback;
        this.merchants = merchants;
    }

    @Override
    public boolean setOnlineQuota(String entityNo, String storeNo, String goodsNo, String skuNo, int qty) {
        String store = resolveStore(entityNo, storeNo);
        if (store == null || !stockSync.enabled(store) || invManaged.managedSkus(List.of(skuNo)).isEmpty()) {
            return false;
        }
        // 先把数写下去；写不成（进销存里没这件货的账）就把这次「改库存」还给调用方按老路走
        if (!writeback.quotaOne(entityNo, store, skuNo, qty,
                "QUOTA:" + skuNo + ":" + System.currentTimeMillis())) {
            return false;
        }
        // 规则留着：往后进货 / 盘点的写回按「手动」只压不抬，不会把店主定的数顶上去
        stockSync.saveRule(store, PrdSellRule.SCOPE_GOODS, goodsNo, PrdSellRule.MANUAL, Math.max(0, qty), OPERATOR);
        log.info("[stock-quota] 改库存转成线上额度：store={} goods={} sku={} qty={}", store, goodsNo, skuNo, qty);
        return true;
    }

    /**
     * 调用方没给门店（主体级库存那条路）时：<b>只有单店主体才敢认</b>。
     * 多店时不知道改的是哪家，认错一家等于把别人的线上额度改了。
     */
    private String resolveStore(String entityNo, String storeNo) {
        if (storeNo != null && !storeNo.isBlank()) {
            return storeNo;
        }
        List<String> stores = merchants.storeNos(entityNo);
        return stores.size() == 1 ? stores.get(0) : null;
    }
}
