package ai.neargo.shop.inventory.api.biz;

import ai.neargo.shop.inventory.config.ConditionalOnInventory;
import ai.neargo.shop.auth.BizContext;
import ai.neargo.shop.auth.BizPerms;
import ai.neargo.shop.auth.SecurityUtils;
import ai.neargo.shop.inventory.dto.InventoryVOs;
import ai.neargo.shop.inventory.service.InboundService;
import ai.neargo.shop.inventory.service.InventoryAclService;
import ai.neargo.shop.inventory.service.LocationService;
import ai.neargo.shop.inventory.service.OutboundService;
import ai.neargo.shop.inventory.service.StockCountService;
import ai.neargo.shop.inventory.service.TransferService;
import org.springframework.context.annotation.Profile;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 商家端 · 单据（s03 盘点 · s04 进货 · s05 单据 · s06 报损 · s07 调拨）。
 *
 * <p>按**资源**拆而不按屏拆：一屏可能用到两个资源，一个资源也可能出现在两屏 ——
 * 按屏拆的话，改一次交互就要动控制器的边界。
 *
 * <p><b>没有创建销售出库的入口</b>：它只能由预留 {@code commit} 产生。
 * 这里放行的话，商家能凭空造一笔销量，而动销榜、毛利、月报全按它算。
 */
@Profile("api")
@ConditionalOnInventory
@RestController
public class BizStockDocController {

    private final InboundService inbound;
    private final OutboundService outbound;
    private final StockCountService counts;
    private final TransferService transfers;
    private final InventoryAclService acl;
    private final LocationService locations;

    public BizStockDocController(InboundService inbound, OutboundService outbound,
                                 StockCountService counts, TransferService transfers,
                                 InventoryAclService acl, LocationService locations) {
        this.inbound = inbound;
        this.outbound = outbound;
        this.counts = counts;
        this.transfers = transfers;
        this.acl = acl;
        this.locations = locations;
    }

    // ── 入库 ──────────────────────────────────────────────────────────
    @PreAuthorize("@perm.canBiz('" + BizPerms.STOCK + "')")
    @PostMapping("/biz/inventory/inbounds")
    public InventoryVOs.DocNoVO createInbound(@RequestBody InboundReq req) {
        return new InventoryVOs.DocNoVO(inbound.createDraft(draftOf(req)));
    }

    @PreAuthorize("@perm.canBiz('" + BizPerms.STOCK + "')")
    @PutMapping("/biz/inventory/inbounds/{no}")
    public void updateInbound(@PathVariable String no, @RequestBody InboundReq req) {
        inbound.updateDraft(owner(), no, draftOf(req));
    }

    /** 过账。**请求体里不带数量** —— 数量在草稿里已经存好了，再带一次就有了「以哪份为准」的问题。 */
    @PreAuthorize("@perm.canBiz('" + BizPerms.STOCK + "')")
    @PostMapping("/biz/inventory/inbounds/{no}/post")
    public void postInbound(@PathVariable String no) {
        inbound.post(owner(), no, SecurityUtils.currentUserNo());
    }

    @PreAuthorize("@perm.canBiz('" + BizPerms.STOCK + "')")
    @PostMapping("/biz/inventory/inbounds/{no}/void")
    public void voidInbound(@PathVariable String no) {
        inbound.voidOrder(owner(), no, SecurityUtils.currentUserNo());
    }

    // ── 出库（报损 / 领用；SALE 被 Service 挡住） ──────────────────────
    @PreAuthorize("@perm.canBiz('" + BizPerms.STOCK + "')")
    @PostMapping("/biz/inventory/outbounds")
    public InventoryVOs.DocNoVO createOutbound(@RequestBody OutboundReq req) {
        List<OutboundService.Line> lines = req.lines().stream()
                .map(l -> new OutboundService.Line(l.itemId(), l.qty(), l.uom())).toList();
        return new InventoryVOs.DocNoVO(outbound.createDraft(new OutboundService.Draft(
                owner(), location(), req.purpose(), null, null, req.reasonCode(),
                req.occurredAt(), req.remark(), req.targetType(), req.targetNo(), lines)));
    }

    @PreAuthorize("@perm.canBiz('" + BizPerms.STOCK + "')")
    @PostMapping("/biz/inventory/outbounds/{no}/post")
    public void postOutbound(@PathVariable String no) {
        outbound.post(owner(), no, SecurityUtils.currentUserNo());
    }

    @PreAuthorize("@perm.canBiz('" + BizPerms.STOCK + "')")
    @PostMapping("/biz/inventory/outbounds/{no}/void")
    public void voidOutbound(@PathVariable String no) {
        outbound.voidOrder(owner(), no, SecurityUtils.currentUserNo());
    }

    // ── 盘点 ──────────────────────────────────────────────────────────
    @PreAuthorize("@perm.canBiz('" + BizPerms.STOCK + "')")
    @PostMapping("/biz/inventory/counts")
    public InventoryVOs.DocNoVO openCount(@RequestBody CountOpenReq req) {
        return new InventoryVOs.DocNoVO(
                counts.open(owner(), location(), req.itemIds(), SecurityUtils.currentUserNo()));
    }

    /**
     * 读回一张盘点单。<b>{@code bookQty} 是开单那一刻的快照</b>，端上直接用它算差异 ——
     * 拿当前余额顶替的话，盘的过程中卖掉的量会被算成盘亏。
     */
    @PreAuthorize("@perm.canBiz('" + BizPerms.STOCK + "')")
    @GetMapping("/biz/inventory/counts/{no}")
    public InventoryVOs.CountVO count(@PathVariable String no) {
        return counts.detail(owner(), no);
    }

    @PreAuthorize("@perm.canBiz('" + BizPerms.STOCK + "')")
    @PutMapping("/biz/inventory/counts/{no}/lines")
    public void fillCount(@PathVariable String no, @RequestBody List<StockCountService.Filled> lines) {
        counts.fill(owner(), no, lines);
    }

    @PreAuthorize("@perm.canBiz('" + BizPerms.STOCK + "')")
    @PostMapping("/biz/inventory/counts/{no}/post")
    public void postCount(@PathVariable String no) {
        counts.post(owner(), no, SecurityUtils.currentUserNo());
    }

    // ── 调拨 ──────────────────────────────────────────────────────────
    @PreAuthorize("@perm.canBiz('" + BizPerms.STOCK + "')")
    @PostMapping("/biz/inventory/transfers")
    public InventoryVOs.DocNoVO createTransfer(@RequestBody TransferReq req) {
        List<TransferService.Line> lines = req.lines().stream()
                .map(l -> new TransferService.Line(l.itemId(), l.qty())).toList();
        return new InventoryVOs.DocNoVO(transfers.create(owner(), req.fromLocationId(), req.toLocationId(),
                lines, SecurityUtils.currentUserNo()));
    }

    /** 读回一张调拨单。**草稿态没有行**（行在发出的那张出库单上），不是空单 */
    @PreAuthorize("@perm.canBiz('" + BizPerms.STOCK + "')")
    @GetMapping("/biz/inventory/transfers/{no}")
    public InventoryVOs.TransferVO transfer(@PathVariable String no) {
        return transfers.detail(owner(), no);
    }

    @PreAuthorize("@perm.canBiz('" + BizPerms.STOCK + "')")
    @PostMapping("/biz/inventory/transfers/{no}/ship")
    public void ship(@PathVariable String no, @RequestBody(required = false) ShipReq req) {
        // 请求体可空：自己送、没记承运方也要发得出去 —— 强制填的话商家就学会乱填一个
        ShipReq r = req == null ? new ShipReq(null, null, null) : req;
        transfers.ship(owner(), no, r.carrierNo(), r.carrierName(), r.trackingNo(),
                SecurityUtils.currentUserNo());
    }

    @PreAuthorize("@perm.canBiz('" + BizPerms.STOCK + "')")
    @PostMapping("/biz/inventory/transfers/{no}/receive")
    public void receive(@PathVariable String no) {
        transfers.receive(owner(), no, SecurityUtils.currentUserNo());
    }

    /**
     * 作废一张<b>还没发出</b>的调拨单 —— 「建错了怎么办」的答案。
     *
     * <p>此前调拨没有任何撤销路径：单据列表的作废按钮只放行出入库单，
     * 而调拨详情页也没有口子，于是建错一张就永远挂在那儿（2026-09-02 生产上
     * 有三张这样的草稿）。已发出的不给作废，见 {@link TransferService#cancel}。
     */
    @PreAuthorize("@perm.canBiz('" + BizPerms.STOCK + "')")
    @PostMapping("/biz/inventory/transfers/{no}/void")
    public void voidTransfer(@PathVariable String no) {
        transfers.cancel(owner(), no, SecurityUtils.currentUserNo());
    }

    // ── 请求体 ────────────────────────────────────────────────────────
    public record LineReq(String itemId, int qty, String uom, Long unitCostMinor) {
    }

    /**
     * @param carrierName <b>端上带下来的名字快照</b>。承运方档案在主库，
     *                    进销存读不了它 —— 让端上把选中那条的名字一起发过来，
     *                    比在服务层做一次跨库查询干净得多。
     */
    public record ShipReq(String carrierNo, String carrierName, String trackingNo) {
    }

    public record InboundReq(String sourceType, String supplierNo, String supplierName,
                            LocalDateTime occurredAt,
                             String remark, List<LineReq> lines) {
    }

    /**
     * @param targetType 去向类型（{@code SUPPLIER}），空 = 没有去向（报损）
     * @param targetNo   去向对象编号。<b>名字不收</b> —— 由服务端查了写快照，
     *                   端上传的话，改个名字就能让历史单据说谎
     */
    public record OutboundReq(String purpose, String reasonCode, LocalDateTime occurredAt,
                              String remark, String targetType, String targetNo,
                              List<LineReq> lines) {
    }

    public record CountOpenReq(List<String> itemIds) {
    }

    public record TransferReq(String fromLocationId, String toLocationId, List<LineReq> lines) {
    }

    // ────────────────────────────────────────────────────────────────
    private InboundService.Draft draftOf(InboundReq req) {
        List<InboundService.Line> lines = req.lines().stream()
                .map(l -> new InboundService.Line(l.itemId(), l.qty(), l.uom(), l.unitCostMinor()))
                .toList();
        return new InboundService.Draft(owner(), location(), req.sourceType(), null,
                req.supplierNo(), req.supplierName(), req.occurredAt(), req.remark(), lines);
    }

    private String owner() {
        return acl.ownerIdOf(BizContext.requireMerchantNo());
    }

    private String location() {
        BizContext ctx = BizContext.current();
        return locations.resolveStockLocation(acl.ownerIdOf(ctx.merchantNo()),
                acl.locationIdOf(ctx.merchantNo(), ctx.currentStoreNo()));
    }
}
