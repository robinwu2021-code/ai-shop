package ai.neargo.shop.invbridge;

import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.inventory.config.ConditionalOnInventory;
import ai.neargo.shop.inventory.service.InventoryAclService;
import ai.neargo.shop.inventory.service.LocationService;
import ai.neargo.shop.inventory.service.StockCountService;
import ai.neargo.shop.inventory.support.InvEnums;
import ai.neargo.shop.product.entity.PrdGoods;
import ai.neargo.shop.product.entity.PrdSellRule;
import ai.neargo.shop.product.entity.PrdSku;
import ai.neargo.shop.product.entity.PrdStoreStockSync;
import ai.neargo.shop.product.service.InvManagedService;
import ai.neargo.shop.product.service.StockSyncService;
import ai.neargo.shop.spi.user.MerchantQueryPort;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 门店库存同步：开关、期初对齐、线上可售规则（TDD-商品纳入进销存开关 §4 / §7 / §18.4–18.5）。
 * 控制器只管鉴权与参数，这里是业务。
 */
@Service
@ConditionalOnInventory
public class StockSyncAppService {

    private final StockSyncService stockSync;
    private final InvManagedService invManaged;
    private final InventoryAclService acl;
    private final StockCountService counts;
    private final MerchantQueryPort merchants;
    private final InventoryWritebackService writeback;
    private final LocationService locations;

    public StockSyncAppService(StockSyncService stockSync, InvManagedService invManaged, InventoryAclService acl,
                               StockCountService counts, MerchantQueryPort merchants,
                               InventoryWritebackService writeback, LocationService locations) {
        this.locations = locations;
        this.stockSync = stockSync;
        this.invManaged = invManaged;
        this.acl = acl;
        this.counts = counts;
        this.merchants = merchants;
        this.writeback = writeback;
    }

    /** 门店必须是这个主体的 —— 路径里的门店号是端上传的，不信 */
    public void requireStore(String entityNo, String storeNo) {
        if (storeNo == null || !merchants.storeNos(entityNo).contains(storeNo)) {
            throw BizException.of(ErrorCode.NOT_FOUND);
        }
    }

    // ------------------------------------------------------------------ 状态与开关

    /**
     * @param state NOT_ALIGNED 还没做期初对齐 / ALIGNED 已对齐未开启 / SYNCING 同步中
     */
    public record SyncState(String storeNo, String state, boolean enabled, Long alignedAt, String alignMode) {
    }

    public SyncState state(String storeNo) {
        PrdStoreStockSync s = stockSync.syncOf(storeNo);
        if (s == null || s.getAlignedAt() == null) {
            return new SyncState(storeNo, "NOT_ALIGNED", false, null, null);
        }
        boolean on = s.getEnabled() != null && s.getEnabled() == 1;
        return new SyncState(storeNo, on ? "SYNCING" : "ALIGNED", on, epochMs(s.getAlignedAt()), s.getAlignMode());
    }

    public SyncState setEnabled(String entityNo, String storeNo, boolean enabled, String operator) {
        stockSync.setEnabled(entityNo, storeNo, enabled, operator);
        if (enabled) {
            // 打开那一刻按当前规则对齐一次：从关着到开着这段时间里的单据不会再补发
            writeback.syncStore(entityNo, storeNo, "ENABLE:" + storeNo + ":" + System.currentTimeMillis(), false);
        }
        return state(storeNo);
    }

    // ------------------------------------------------------------------ 期初对齐

    /**
     * 对齐清单的一行。{@code mallStock} 是商城总量（含已锁定），与进销存的实存同口径。
     *
     * @param note ENTITY_MULTI_STORE 这件货在商城是「全店共用一个数」而主体有多家店 —— 先按店设一次库存才能同步；
     *             NO_ITEM 进销存里还没有这件货
     */
    public record AlignRow(String goodsNo, String title, String skuNo, String spec, Integer onHand, Integer reserved,
                           int mallStock, int mallLocked, Integer diff, String note) {
    }

    public List<AlignRow> alignment(String entityNo, String storeNo) {
        boolean multi = merchants.storeNos(entityNo).size() > 1;
        List<PrdGoods> goods = invManaged.managedGoods(entityNo);
        Map<String, PrdGoods> byNo = goods.stream().collect(Collectors.toMap(PrdGoods::getGoodsNo, g -> g, (a, b) -> a));
        List<AlignRow> out = new ArrayList<>();
        for (PrdSku s : invManaged.skusOf(byNo.keySet())) {
            PrdGoods g = byNo.get(s.getGoodsNo());
            StockSyncService.Sellable mall = stockSync.sellableOf(storeNo, s.getSkuNo());
            int mallStock = mall.sellable() + mall.locked();
            InventoryAclService.StockAt st = acl.stockAt(entityNo, storeNo, s.getSkuNo());
            String note = st == null ? "NO_ITEM"
                    : ("ENTITY".equals(mall.mode()) && multi ? "ENTITY_MULTI_STORE" : null);
            out.add(new AlignRow(g.getGoodsNo(), g.getTitle(), s.getSkuNo(), s.getSpec(),
                    st == null ? null : st.onHand(), st == null ? null : st.reserved(),
                    mallStock, mall.locked(), st == null ? null : st.onHand() - mallStock, note));
        }
        return out;
    }

    /**
     * 确认期初对齐。
     *
     * <ul>
     *   <li>{@code MALL} 以商城为准：有差额的行各开一张盘点，把实存调成商城总量（含锁定）</li>
     *   <li>{@code COUNT} 已实地盘点：实存本来就对，只记对齐时间</li>
     * </ul>
     * 两种都随后按规则写回一次（{@code ALIGN:…}）。<b>不自动打开同步</b> —— 打开是另一个动作，让店主看过结果再开。
     *
     * @return 调了几行实存
     */
    public int confirmAlignment(String entityNo, String storeNo, String mode, String operator) {
        int adjusted = 0;
        if (PrdStoreStockSync.ALIGN_MALL.equals(mode)) {
            String ownerId = acl.ownerIdOf(entityNo);
            // 写路径：按需建库位（与进货、盘点同一套解析），只读的 stockLocationOf 在新店上是空
            String locationId = locations.resolveStockLocation(ownerId, acl.locationIdOf(entityNo, storeNo));
            for (AlignRow r : alignment(entityNo, storeNo)) {
                if (r.note() != null || r.diff() == null || r.diff() == 0) {
                    continue;
                }
                counts.adjustOne(ownerId, locationId, acl.itemIdOfSku(r.skuNo()), r.mallStock(),
                        InvEnums.Reason.CHECK, operator);
                adjusted++;
            }
        }
        stockSync.markAligned(entityNo, storeNo, mode, operator);
        writeback.syncStore(entityNo, storeNo, "ALIGN:" + storeNo + ":" + System.currentTimeMillis(), false);
        return adjusted;
    }

    // ------------------------------------------------------------------ 规则

    public record RuleRow(String scopeType, String scopeRef, String ruleType, int param) {
    }

    public List<RuleRow> rules(String storeNo) {
        return stockSync.rules(storeNo).stream()
                .map(r -> new RuleRow(r.getScopeType(), r.getScopeRef(), r.getRuleType(),
                        r.getParam() == null ? 0 : r.getParam()))
                .toList();
    }

    /** 存规则并让受影响的商品在本店重算一次（本店没开同步则只存） */
    public RuleRow saveRule(String entityNo, String storeNo, String scopeType, String scopeRef, String ruleType,
                            int param, String operator) {
        PrdSellRule r = stockSync.saveRule(storeNo, scopeType, scopeRef, ruleType, param, operator);
        List<String> affected = invManaged.managedGoods(entityNo).stream()
                .filter(g -> switch (r.getScopeType()) {
                    case PrdSellRule.SCOPE_GOODS -> r.getScopeRef().equals(g.getGoodsNo());
                    case PrdSellRule.SCOPE_CATEGORY -> r.getScopeRef().equals(g.getCategoryNo());
                    default -> true;
                })
                .map(PrdGoods::getGoodsNo).toList();
        writeback.syncGoods(entityNo, storeNo, affected, "RULE:" + r.getId() + ":" + System.currentTimeMillis());
        return new RuleRow(r.getScopeType(), r.getScopeRef(), r.getRuleType(), r.getParam());
    }

    private static Long epochMs(LocalDateTime t) {
        return t == null ? null : t.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();
    }
}
