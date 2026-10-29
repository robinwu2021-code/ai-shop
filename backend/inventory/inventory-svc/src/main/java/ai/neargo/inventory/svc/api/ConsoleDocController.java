package ai.neargo.inventory.svc.api;

import ai.neargo.inventory.api.InventoryError;
import ai.neargo.inventory.api.InventoryException;
import ai.neargo.inventory.domain.dto.InventoryVOs;
import ai.neargo.inventory.domain.service.Caller;
import ai.neargo.inventory.domain.service.HostReferenceService;
import ai.neargo.inventory.domain.service.InboundService;
import ai.neargo.inventory.domain.service.InventoryAclService;
import ai.neargo.inventory.domain.service.OutboundService;
import ai.neargo.inventory.domain.service.StockCountService;
import ai.neargo.inventory.domain.service.TransferService;
import ai.neargo.inventory.guard.InventoryRequestContext;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Set;

/**
 * 商家后台 · 单据（M8 T8.3，读写全切）：商城 {@code BizStockDocController} 的服务端对应 —— 入库、出库、盘点、调拨。
 *
 * <p><b>数据范围随请求带来</b>：只管部分门店的员工，商城在每个请求上附 {@code scopeStores}（门店号，逗号分隔），
 * 单据涉及的库位与这些门店的库位不相交就 403，规则与内嵌逐字相同；店主（整个商家）不带。
 * {@code storeRef} = 当前门店号，新单据落在它解析出的库位上。
 */
@RestController
public class ConsoleDocController {

    private static final String BASE = "/api/inventory/v1/console/docs";

    private final InboundService inbound;
    private final OutboundService outbound;
    private final StockCountService counts;
    private final TransferService transfers;
    private final InventoryAclService acl;
    private final HostReferenceService refs;

    public ConsoleDocController(InboundService inbound, OutboundService outbound, StockCountService counts,
                                TransferService transfers, InventoryAclService acl, HostReferenceService refs) {
        this.inbound = inbound;
        this.outbound = outbound;
        this.counts = counts;
        this.transfers = transfers;
        this.acl = acl;
        this.refs = refs;
    }

    // ── 入库 ──────────────────────────────────────────────────────────

    @PostMapping(BASE + "/inbounds")
    public InventoryVOs.DocNoVO createInbound(@RequestBody InboundReq req, @RequestParam(required = false) String storeRef) {
        return new InventoryVOs.DocNoVO(inbound.createDraft(draftOf(req, storeRef)));
    }

    @PutMapping(BASE + "/inbounds/{no}")
    public void updateInbound(@PathVariable String no, @RequestBody InboundReq req,
                              @RequestParam(required = false) String storeRef,
                              @RequestParam(required = false) String scopeStores) {
        inbound.updateDraft(docOwner(no, scopeStores), no, draftOf(req, storeRef));
    }

    @PostMapping(BASE + "/inbounds/{no}/post")
    public void postInbound(@PathVariable String no, @RequestParam(required = false) String scopeStores) {
        inbound.post(docOwner(no, scopeStores), no, operator());
    }

    @PostMapping(BASE + "/inbounds/{no}/void")
    public void voidInbound(@PathVariable String no, @RequestParam(required = false) String scopeStores) {
        inbound.voidOrder(docOwner(no, scopeStores), no, operator());
    }

    // ── 出库 ──────────────────────────────────────────────────────────

    @PostMapping(BASE + "/outbounds")
    public InventoryVOs.DocNoVO createOutbound(@RequestBody OutboundReq req,
                                               @RequestParam(required = false) String storeRef) {
        List<OutboundService.Line> lines = req.lines().stream()
                .map(l -> new OutboundService.Line(l.itemId(), l.qty(), l.uom())).toList();
        return new InventoryVOs.DocNoVO(outbound.createDraft(new OutboundService.Draft(owner(), store(storeRef),
                req.purpose(), null, null, req.reasonCode(), req.occurredAt(), req.remark(), req.targetType(),
                req.targetNo(), lines)));
    }

    @PostMapping(BASE + "/outbounds/{no}/post")
    public void postOutbound(@PathVariable String no, @RequestParam(required = false) String scopeStores) {
        outbound.post(docOwner(no, scopeStores), no, operator());
    }

    @PostMapping(BASE + "/outbounds/{no}/void")
    public void voidOutbound(@PathVariable String no, @RequestParam(required = false) String scopeStores) {
        outbound.voidOrder(docOwner(no, scopeStores), no, operator());
    }

    // ── 盘点 ──────────────────────────────────────────────────────────

    @PostMapping(BASE + "/counts")
    public InventoryVOs.DocNoVO openCount(@RequestBody CountOpenReq req, @RequestParam(required = false) String storeRef) {
        return new InventoryVOs.DocNoVO(counts.open(owner(), store(storeRef), req.itemIds(), operator()));
    }

    @GetMapping(BASE + "/counts/{no}")
    public InventoryVOs.CountVO count(@PathVariable String no, @RequestParam(required = false) String scopeStores) {
        return counts.detail(docOwner(no, scopeStores), no);
    }

    @PutMapping(BASE + "/counts/{no}/lines")
    public void fillCount(@PathVariable String no, @RequestBody List<StockCountService.Filled> lines,
                          @RequestParam(required = false) String scopeStores) {
        counts.fill(docOwner(no, scopeStores), no, lines);
    }

    @PostMapping(BASE + "/counts/{no}/post")
    public void postCount(@PathVariable String no, @RequestParam(required = false) String scopeStores) {
        counts.post(docOwner(no, scopeStores), no, operator());
    }

    @PostMapping(BASE + "/counts/{no}/void")
    public void voidCount(@PathVariable String no, @RequestParam(required = false) String scopeStores) {
        counts.cancel(docOwner(no, scopeStores), no, operator());
    }

    // ── 调拨 ──────────────────────────────────────────────────────────

    @PostMapping(BASE + "/transfers")
    public InventoryVOs.DocNoVO createTransfer(@RequestBody TransferReq req,
                                               @RequestParam(required = false) String scopeStores) {
        String owner = owner();
        // 两头都要是他动得到的库位
        for (String loc : Arrays.asList(req.fromLocationId(), req.toLocationId())) {
            if (loc != null) {
                requireScope(owner, List.of(loc), scopeStores);
            }
        }
        List<TransferService.Line> lines = req.lines().stream()
                .map(l -> new TransferService.Line(l.itemId(), l.qty())).toList();
        return new InventoryVOs.DocNoVO(transfers.create(owner, req.fromLocationId(), req.toLocationId(), lines,
                operator()));
    }

    @GetMapping(BASE + "/transfers/{no}")
    public InventoryVOs.TransferVO transfer(@PathVariable String no, @RequestParam(required = false) String scopeStores) {
        return transfers.detail(docOwner(no, scopeStores), no);
    }

    @PostMapping(BASE + "/transfers/{no}/ship")
    public void ship(@PathVariable String no, @RequestBody(required = false) ShipReq req,
                     @RequestParam(required = false) String scopeStores) {
        ShipReq r = req == null ? new ShipReq(null, null, null) : req;
        transfers.ship(docOwner(no, scopeStores), no, r.carrierNo(), r.carrierName(), r.trackingNo(), operator());
    }

    @PostMapping(BASE + "/transfers/{no}/receive")
    public void receive(@PathVariable String no, @RequestParam(required = false) String scopeStores) {
        transfers.receive(docOwner(no, scopeStores), no, operator());
    }

    @PostMapping(BASE + "/transfers/{no}/void")
    public void voidTransfer(@PathVariable String no, @RequestParam(required = false) String scopeStores) {
        transfers.cancel(docOwner(no, scopeStores), no, operator());
    }

    // ── 请求体（与商城端点同形） ──────────────────────────────────────

    public record LineReq(String itemId, int qty, String uom, Long unitCostMinor, String batchNo, LocalDate expireAt) {
    }

    public record ShipReq(String carrierNo, String carrierName, String trackingNo) {
    }

    public record InboundReq(String sourceType, String supplierNo, String supplierName, LocalDateTime occurredAt,
                             String remark, List<LineReq> lines) {
    }

    public record OutboundReq(String purpose, String reasonCode, LocalDateTime occurredAt, String remark,
                              String targetType, String targetNo, List<LineReq> lines) {
    }

    public record CountOpenReq(List<String> itemIds) {
    }

    public record TransferReq(String fromLocationId, String toLocationId, List<LineReq> lines) {
    }

    // ────────────────────────────────────────────────────────────────

    private InboundService.Draft draftOf(InboundReq req, String storeRef) {
        List<InboundService.Line> lines = req.lines().stream()
                .map(l -> new InboundService.Line(l.itemId(), l.qty(), l.uom(), l.unitCostMinor(), l.batchNo(),
                        l.expireAt()))
                .toList();
        return new InboundService.Draft(owner(), store(storeRef), req.sourceType(), null, req.supplierNo(),
                req.supplierName(), req.occurredAt(), req.remark(), lines);
    }

    private String docOwner(String no, String scopeStores) {
        String owner = owner();
        requireScope(owner, acl.locationsOfDoc(owner, no), scopeStores);
        return owner;
    }

    /** 与商城内嵌的 {@code requireLocationAccess} 同一条规则：不带范围（店主）不判；单据没库位不判 */
    private void requireScope(String owner, Collection<String> docLocations, String scopeStores) {
        if (scopeStores == null || docLocations.isEmpty()) {
            return;
        }
        Set<String> mine = acl.locationsOfStoresOf(owner,
                Arrays.stream(scopeStores.split(",")).map(String::strip).filter(s -> !s.isEmpty()).toList());
        if (docLocations.stream().noneMatch(mine::contains)) {
            throw InventoryException.of(InventoryError.FORBIDDEN, "scope");
        }
    }

    private String store(String storeRef) {
        return refs.storeLocationOf(owner(), storeRef);
    }

    private static String owner() {
        return InventoryRequestContext.caller().ownerId();
    }

    private static String operator() {
        Caller caller = InventoryRequestContext.caller();
        return caller.actor() == null ? caller.systemCode() : caller.actor();
    }
}
