package ai.neargo.shop.invbridge;

import ai.neargo.shop.event.SysOutbox;
import ai.neargo.shop.event.SysOutboxMapper;
import ai.neargo.shop.inventory.config.ConditionalOnInventory;
import ai.neargo.shop.inventory.service.InventoryAclService;
import ai.neargo.shop.product.entity.PrdGoods;
import ai.neargo.shop.product.entity.PrdSku;
import ai.neargo.shop.product.service.InvManagedService;
import ai.neargo.shop.product.service.StockSyncService;
import ai.neargo.shop.spi.user.MerchantQueryPort;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Objects;

/**
 * 进销存 → 商城写回的编排（TDD-商品纳入进销存开关 §6 / §18.3）。
 *
 * <p>商品域（{@link StockSyncService}）管规则与改商城，进销存（{@link InventoryAclService}）管实存与占用，
 * 两边只在这里碰头。三个入口共用 {@link #syncOne}：单据过账、规则变更、每晚兜底 / 期初对齐。
 *
 * <h2>为什么要「等追平」</h2>
 * 线上下单是商城先锁、几秒后镜像才把占用记到进销存（{@code INV_MIRROR_RESERVE}）。这几秒里
 * 进销存的可用多算了一件，按它写回会把刚锁掉的那件又放出来 —— 超卖。所以这个 SKU 还有没投完的
 * RESERVE / ADJUST 镜像时<b>抛出去让投递器稍后重投</b>。COMMIT / RELEASE / RESTORE 没追平只会让线上少放，不等。
 */
@Service
@ConditionalOnInventory
public class InventoryWritebackService {

    private static final Logger log = LoggerFactory.getLogger(InventoryWritebackService.class);
    private static final String OPERATOR = "STOCK_SYNC";

    private final StockSyncService stockSync;
    private final InvManagedService invManaged;
    private final InventoryAclService acl;
    private final MerchantQueryPort merchants;
    private final SysOutboxMapper outbox;

    public InventoryWritebackService(StockSyncService stockSync, InvManagedService invManaged,
                                     InventoryAclService acl, MerchantQueryPort merchants, SysOutboxMapper outbox) {
        this.stockSync = stockSync;
        this.invManaged = invManaged;
        this.acl = acl;
        this.merchants = merchants;
        this.outbox = outbox;
    }

    /** 镜像还没追平：抛给投递器，按它的退避稍后重投 */
    public static class MirrorNotCaughtUp extends RuntimeException {
        public MirrorNotCaughtUp(String skuNo) {
            super("镜像未追平，稍后重试：skuNo=" + skuNo);
        }
    }

    /** 一张单据过账后：它动到的每个（门店, SKU）各算一次。门店按「出货库位 = 这一行的库位」认 */
    public int onDocumentPosted(String docNo) {
        int n = 0;
        for (InventoryAclService.PostedLine line : acl.postedLines(docNo)) {
            for (String storeNo : merchants.storeNos(line.entityNo())) {
                if (!stockSync.enabled(storeNo)
                        || !Objects.equals(acl.stockLocationOf(line.entityNo(), storeNo), line.locationId())) {
                    continue;
                }
                if (syncOne(line.entityNo(), storeNo, line.skuNo(), docNo) != null) {
                    n++;
                }
            }
        }
        return n;
    }

    /** 整店重算（期初对齐、每晚兜底）：本主体接入进销存的每个 SKU 一次 */
    public List<StockSyncService.Result> syncStore(String entityNo, String storeNo, String sourceRef,
                                                   boolean requireEnabled) {
        List<StockSyncService.Result> out = new ArrayList<>();
        if (requireEnabled && !stockSync.enabled(storeNo)) {
            return out;
        }
        List<String> goodsNos = invManaged.managedGoods(entityNo).stream().map(PrdGoods::getGoodsNo).toList();
        for (PrdSku s : invManaged.skusOf(goodsNos)) {
            StockSyncService.Result r = syncOne(entityNo, storeNo, s.getSkuNo(), sourceRef, false);
            if (r != null) {
                out.add(r);
            }
        }
        return out;
    }

    /** 规则变了：受影响的这几件商品在这家店各算一次 */
    public void syncGoods(String entityNo, String storeNo, Collection<String> goodsNos, String sourceRef) {
        if (!stockSync.enabled(storeNo)) {
            return;
        }
        for (PrdSku s : invManaged.skusOf(goodsNos)) {
            syncOne(entityNo, storeNo, s.getSkuNo(), sourceRef);
        }
    }

    StockSyncService.Result syncOne(String entityNo, String storeNo, String skuNo, String sourceRef) {
        return syncOne(entityNo, storeNo, skuNo, sourceRef, true);
    }

    /**
     * @return 没写（门店没开 / 不接入 / 进销存里没有这件货）返回 {@code null}
     */
    StockSyncService.Result syncOne(String entityNo, String storeNo, String skuNo, String sourceRef,
                                    boolean requireEnabled) {
        if (requireEnabled && !stockSync.enabled(storeNo)) {
            return null;
        }
        if (invManaged.managedSkus(List.of(skuNo)).isEmpty()) {
            return null;
        }
        if (mirrorPending(skuNo)) {
            throw new MirrorNotCaughtUp(skuNo);
        }
        InventoryAclService.StockAt st = acl.stockAt(entityNo, storeNo, skuNo);
        if (st == null) {
            return null;
        }
        StockSyncService.Result r = stockSync.apply(entityNo, storeNo, skuNo, st.available(), sourceRef, OPERATOR);
        if (r.skipped() != null && !"DONE_BEFORE".equals(r.skipped())) {
            log.info("[stock-sync] 跳过 store={} sku={} src={} 原因={}", storeNo, skuNo, sourceRef, r.skipped());
        }
        return r;
    }

    /** 这个 SKU 还有没投完的 RESERVE / ADJUST 镜像吗 */
    boolean mirrorPending(String skuNo) {
        Long n = outbox.selectCount(Wrappers.<SysOutbox>lambdaQuery()
                .eq(SysOutbox::getStatus, SysOutbox.PENDING)
                .in(SysOutbox::getEventType, InvMirrorEvent.RESERVE, InvMirrorEvent.ADJUST)
                .like(SysOutbox::getPayload, "\"" + skuNo + "\""));
        return n != null && n > 0;
    }
}
