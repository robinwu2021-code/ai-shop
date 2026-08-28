package ai.neargo.shop.inventory.api.biz;

import ai.neargo.shop.inventory.config.ConditionalOnInventory;
import ai.neargo.shop.auth.BizContext;
import ai.neargo.shop.auth.BizPerms;
import ai.neargo.shop.auth.SecurityUtils;
import ai.neargo.shop.inventory.dto.InventoryVOs.BalanceVO;
import ai.neargo.shop.inventory.dto.InventoryVOs.ItemDetailVO;
import ai.neargo.shop.inventory.dto.InventoryVOs.LedgerPageVO;
import ai.neargo.shop.inventory.dto.InventoryVOs.SummaryVO;
import ai.neargo.shop.inventory.service.InventoryAclService;
import ai.neargo.shop.inventory.service.LocationService;
import ai.neargo.shop.inventory.service.StockCountService;
import ai.neargo.shop.inventory.service.StockQueryService;
import org.springframework.context.annotation.Profile;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 商家端 · 库存（s01 库存 · s02 明细）。
 *
 * <p><b>这一层是防腐层的一半</b>：把平台的 {@code entityNo} / {@code storeNo} 翻成本域的
 * {@code ownerId} / {@code locationId}，再往下调。领域服务只认后者 ——
 * <b>独立交付时整个 {@code api/biz} 不部署</b>，客户走的是 Open API。
 *
 * <p>没有「改库存」这个端点：{@code POST /adjust} 底下**是一张单件盘点单**。
 * 所有余额变动都必须有单据，这条不因为「只改一件」而放宽。
 */
@Profile("api")
@ConditionalOnInventory
@RestController
public class BizStockController {

    /** 一页最多给多少行。列表是给人扫的，给到 500 行只会让端上自己再截一次。 */
    private static final int PAGE_MAX = 100;

    private final StockQueryService query;
    private final StockCountService counts;
    private final InventoryAclService acl;
    private final LocationService locations;

    public BizStockController(StockQueryService query, StockCountService counts,
                              InventoryAclService acl, LocationService locations) {
        this.query = query;
        this.counts = counts;
        this.acl = acl;
        this.locations = locations;
    }

    @PreAuthorize("@perm.canBiz('" + BizPerms.STOCK + "')")
    @GetMapping("/biz/inventory/summary")
    public SummaryVO summary() {
        return query.summary(owner(), location());
    }

    @PreAuthorize("@perm.canBiz('" + BizPerms.STOCK + "')")
    @GetMapping("/biz/inventory/balances")
    public List<BalanceVO> balances(@RequestParam(required = false) String filter,
                                    @RequestParam(defaultValue = "50") int size) {
        return query.balances(owner(), location(), filter, Math.min(size, PAGE_MAX));
    }

    /**
     * 可挑的货 —— 开单时选货用。**与 balances 是两个问题**：
     * 那一条问「我有多少」（读余额），这一条问「哪件货」（读物料）。
     *
     * <p>余额行按需建，一件从没进过货的物料没有那一行；从余额出发的话它不存在，
     * 商家<b>没法给它记第一笔进货</b>。2026-08-28 线上就有一件：207 物料、206 行余额。
     */
    @PreAuthorize("@perm.canBiz('" + BizPerms.STOCK + "')")
    @GetMapping("/biz/inventory/pickable")
    public List<BalanceVO> pickable(@RequestParam(required = false) String q,
                                    @RequestParam(defaultValue = "200") int size) {
        return query.pickableItems(owner(), location(), q, Math.min(size, PAGE_MAX));
    }

    @PreAuthorize("@perm.canBiz('" + BizPerms.STOCK + "')")
    @GetMapping("/biz/inventory/items/{itemId}")
    public ItemDetailVO item(@PathVariable String itemId) {
        return query.itemDetail(owner(), itemId);
    }

    @PreAuthorize("@perm.canBiz('" + BizPerms.STOCK + "')")
    @GetMapping("/biz/inventory/ledger")
    public LedgerPageVO ledger(@RequestParam(required = false) String itemId,
                               @RequestParam(required = false) Long cursor,
                               @RequestParam(defaultValue = "20") int size) {
        return query.ledger(owner(), itemId, location(), cursor, Math.min(size, PAGE_MAX));
    }

    /** 单件「改数」。界面上是一个按钮，底下是**开单 → 录一行 → 过账**。 */
    @PreAuthorize("@perm.canBiz('" + BizPerms.STOCK + "')")
    @PostMapping("/biz/inventory/adjust")
    public void adjust(@RequestBody AdjustReq req) {
        counts.adjustOne(owner(), location(), req.itemId(), req.countedQty(),
                req.reasonCode(), SecurityUtils.currentUserNo());
    }

    public record AdjustReq(String itemId, int countedQty, String reasonCode) {
    }

    // ── 防腐：平台键 → 域内键 ──────────────────────────────────────────
    private String owner() {
        return acl.ownerIdOf(BizContext.requireMerchantNo());
    }

    /**
     * 本次请求作用在哪个库位。
     *
     * <p>门店没设发货源时就是它自己；设了就是源仓 —— <b>解析在服务端</b>，
     * 前端只知道门店，不该知道有两套 ID。
     */
    private String location() {
        BizContext ctx = BizContext.current();
        String locationId = acl.locationIdOf(ctx.merchantNo(), ctx.currentStoreNo());
        return locations.resolveStockLocation(acl.ownerIdOf(ctx.merchantNo()), locationId);
    }
}
