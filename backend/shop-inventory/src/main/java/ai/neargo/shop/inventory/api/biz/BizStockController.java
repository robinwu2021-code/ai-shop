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
import ai.neargo.shop.inventory.service.ItemService;
import ai.neargo.shop.inventory.service.LocationService;
import ai.neargo.shop.inventory.service.StockCountService;
import ai.neargo.shop.inventory.service.StockQueryService;
import org.springframework.context.annotation.Profile;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
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
    private final ItemService items;
    private final InventoryAclService acl;
    private final LocationService locations;

    public BizStockController(StockQueryService query, StockCountService counts,
                              ItemService items, InventoryAclService acl,
                              LocationService locations) {
        this.query = query;
        this.counts = counts;
        this.items = items;
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

    /**
     * 扫码找货。<b>三段里的前两段在这里，第三段在端上</b>：
     *
     * <ul>
     *   <li>命中 → 回那件货，端上直接加进单子</li>
     *   <li>没命中 → <b>回 {@code null}，不是 404</b>。「这个码还没绑过」是这个功能的常态
     *       （线上 {@code prd_sku.barcode} 是 0/396），端上据此让商家选一件货绑上</li>
     * </ul>
     *
     * <p><b>标准库那一段（「标准库里是《XX》」）不在这里</b>：它要读 {@code prd_spu_std}，
     * 而那是平台的表，进销存是独立库、读不到。要做的话落点在 {@code shop-app} 的桥接层，
     * 与健康度、对差同一处 —— 这一轮不做。
     */
    @PreAuthorize("@perm.canBiz('" + BizPerms.STOCK + "')")
    @GetMapping("/biz/inventory/items/by-barcode")
    public BalanceVO byBarcode(@RequestParam String code) {
        return query.byBarcode(owner(), location(), code);
    }

    @PreAuthorize("@perm.canBiz('" + BizPerms.STOCK + "')")
    @GetMapping("/biz/inventory/items/{itemId}")
    public ItemDetailVO item(@PathVariable String itemId) {
        return query.itemDetail(owner(), itemId);
    }

    @PreAuthorize("@perm.canBiz('" + BizPerms.STOCK + "')")
    @GetMapping("/biz/inventory/ledger")
    public LedgerPageVO ledger(@RequestParam(required = false) String itemId,
                               @RequestParam(required = false) String docNo,
                               @RequestParam(required = false) Long cursor,
                               @RequestParam(defaultValue = "20") int size) {
        return query.ledger(owner(), itemId, docNo, location(), cursor, Math.min(size, PAGE_MAX));
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

    /**
     * 设安全库存阈值 —— <b>缺货预警的那条线</b>。
     *
     * <p>此前这两列（{@code inv_item.safety_stock} / {@code inv_stock_balance.safety_stock}）
     * 建了、判据写了、界面标红也接了，<b>就是没有任何地方能改它</b>。
     * 默认全 0 = 不预警，于是「缺货」这一档实际只在 {@code available <= 0} 时才亮 ——
     * 预警功能整个是哑的，而库存页第一栏与工作台那张卡的第二个数都指着它。
     *
     * <p><b>不是 POST 是 PUT</b>：设阈值是把一个值放到一个已知位置上，重复设是幂等的。
     *
     * <p><b>{@code itemId} 在 body 不在路径</b>：本模块整个是复数资源名，而
     * {@code /items/{id}/…} 会撞上 ADR-007 那条「资源段用单数」的闸门，
     * 破例就要往 {@code known-plural-paths.txt} 里再加一行。
     * 同模块的另一个单件写口 {@code /biz/inventory/adjust} 早就是这么做的 ——
     * <b>跟着它走，比给一条豁免记录更省事，也更一致</b>。
     */
    @PreAuthorize("@perm.canBiz('" + BizPerms.STOCK + "')")
    @PutMapping("/biz/inventory/safety-stock")
    public void safetyStock(@RequestBody SafetyStockReq req) {
        items.setSafetyStock(owner(), req.itemId(), req.locationId(), req.qty(),
                SecurityUtils.currentUserNo());
    }

    /**
     * @param locationId 空 = 设物料默认值（绝大多数商家只用得到这一级）；
     *                   非空 = 只设该库位
     * @param qty        {@code null} <b>只在设库位覆盖时合法</b>，含义是撤掉覆盖、跟随默认。
     *                   用 {@code Integer} 而不是 {@code int}：后者会把「撤掉」
     *                   静默变成「设成 0」，而 0 是「这个库位不预警」—— 两件事
     */
    public record SafetyStockReq(String itemId, String locationId, Integer qty) {
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
