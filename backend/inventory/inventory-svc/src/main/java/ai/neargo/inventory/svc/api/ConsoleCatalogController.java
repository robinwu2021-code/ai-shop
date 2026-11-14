package ai.neargo.inventory.svc.api;

import ai.neargo.inventory.domain.dto.InventoryVOs;
import ai.neargo.inventory.domain.entity.InvLocation;
import ai.neargo.inventory.domain.entity.InvSupplier;
import ai.neargo.inventory.domain.service.Caller;
import ai.neargo.inventory.domain.service.HostReferenceService;
import ai.neargo.inventory.domain.service.InventoryReportService;
import ai.neargo.inventory.domain.service.InventoryValuationService;
import ai.neargo.inventory.domain.service.LocationService;
import ai.neargo.inventory.domain.service.StockQueryService;
import ai.neargo.inventory.domain.service.SupplierService;
import ai.neargo.inventory.guard.InventoryRequestContext;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * 商家后台 · 库位、供应商、报表、估值（M8 T8.3，读写全切）：商城 {@code BizLocationController}、
 * {@code BizSupplierController}、{@code BizStockReportController}、{@code BizInventoryValuationController} 的服务端对应，
 * 返回结构逐个相同。{@code storeRef} = 当前门店号。
 */
@RestController
public class ConsoleCatalogController {

    private static final String BASE = "/api/inventory/v1/console";
    private static final int PAGE_MAX = 100;

    private final LocationService locations;
    private final SupplierService suppliers;
    private final StockQueryService query;
    private final InventoryReportService reports;
    private final InventoryValuationService valuation;
    private final HostReferenceService refs;

    public ConsoleCatalogController(LocationService locations, SupplierService suppliers, StockQueryService query,
                                    InventoryReportService reports, InventoryValuationService valuation,
                                    HostReferenceService refs) {
        this.locations = locations;
        this.suppliers = suppliers;
        this.query = query;
        this.reports = reports;
        this.valuation = valuation;
        this.refs = refs;
    }

    // ── 库位 ──────────────────────────────────────────────────────────

    @GetMapping(BASE + "/locations")
    public List<InvLocation> locations() {
        return locations.list(owner());
    }

    @PostMapping(BASE + "/locations")
    public InventoryVOs.DocNoVO createWarehouse(@RequestBody NameReq req) {
        return new InventoryVOs.DocNoVO(locations.createWarehouse(owner(), req.name(), operator()));
    }

    @PutMapping(BASE + "/locations/{id}/source")
    public void setSource(@PathVariable String id, @RequestBody SourceReq req) {
        locations.setSource(owner(), id, req.sourceLocationId(), operator());
    }

    // ── 供应商 ────────────────────────────────────────────────────────

    @GetMapping(BASE + "/suppliers")
    public List<SupplierVO> suppliers(@RequestParam(required = false) String keyword,
                                      @RequestParam(defaultValue = "true") boolean activeOnly) {
        return suppliers.list(owner(), keyword, activeOnly).stream().map(SupplierVO::of).toList();
    }

    @PostMapping(BASE + "/suppliers")
    public SupplierNoVO createSupplier(@RequestBody SupplierReq req) {
        return new SupplierNoVO(suppliers.create(owner(), req.toEntity(), operator()));
    }

    @PutMapping(BASE + "/suppliers/{no}")
    public void updateSupplier(@PathVariable String no, @RequestBody SupplierReq req) {
        suppliers.update(owner(), no, req.toEntity(), operator());
    }

    @PostMapping(BASE + "/suppliers/{no}/active")
    public void setSupplierActive(@PathVariable String no, @RequestBody ActiveReq req) {
        suppliers.setActive(owner(), no, req.active(), operator());
    }

    // ── 报表 ──────────────────────────────────────────────────────────

    @GetMapping(BASE + "/documents")
    public List<InventoryVOs.DocumentVO> documents(@RequestParam(required = false) String kind,
                                                   @RequestParam(required = false) String no,
                                                   @RequestParam(required = false) String storeRef,
                                                   @RequestParam(defaultValue = "50") int size) {
        return query.documents(owner(), store(storeRef), kind, no, Math.min(size, PAGE_MAX));
    }

    @GetMapping(BASE + "/report/monthly")
    public InventoryVOs.MonthlyVO monthly(@RequestParam String month, @RequestParam(required = false) String storeRef) {
        return reports.monthly(owner(), store(storeRef), month);
    }

    @GetMapping(BASE + "/report/ranking")
    public List<InventoryVOs.RankVO> ranking(@RequestParam(defaultValue = "fast") String type,
                                             @RequestParam(defaultValue = "30") int days,
                                             @RequestParam(defaultValue = "20") int limit,
                                             @RequestParam(required = false) String storeRef) {
        return reports.ranking(owner(), store(storeRef), type, days, Math.min(limit, PAGE_MAX));
    }

    /** CSV 原文；文件名与审计由商城那一层负责 */
    @GetMapping(BASE + "/export")
    public ResponseEntity<String> export(@RequestParam(defaultValue = "ledger") String type,
                                         @RequestParam(required = false) String storeRef) {
        return ResponseEntity.ok().contentType(new MediaType("text", "csv", StandardCharsets.UTF_8))
                .body(reports.exportCsv(owner(), store(storeRef), type));
    }

    // ── 估值 ──────────────────────────────────────────────────────────

    @GetMapping(BASE + "/items/{item}/valuation")
    public InventoryValuationService.Policy valuation(@PathVariable String item) {
        return valuation.get(owner(), item);
    }

    /** 幂等键是商城调用方给的那一把（经 {@code key} 参数带来），重放才对得上 */
    @PostMapping(BASE + "/items/{item}/valuation")
    public InventoryValuationService.Policy configureValuation(@PathVariable String item, @RequestParam String key,
                                                               @RequestBody InventoryValuationService.Policy req) {
        return valuation.configure(owner(), item, operator(), req, key);
    }

    // ── 请求 / 返回（与商城端点同形） ─────────────────────────────────

    public record NameReq(String name) {
    }

    public record SourceReq(String sourceLocationId) {
    }

    public record ActiveReq(boolean active) {
    }

    public record SupplierNoVO(String supplierNo) {
    }

    public record SupplierReq(String name, String shortName, String contactName, String contactPhone, String remark,
                              String platformSupplierNo) {
        InvSupplier toEntity() {
            InvSupplier e = new InvSupplier();
            e.setName(name);
            e.setShortName(shortName);
            e.setContactName(contactName);
            e.setContactPhone(contactPhone);
            e.setRemark(remark);
            e.setPlatformSupplierNo(platformSupplierNo);
            return e;
        }
    }

    public record SupplierVO(String supplierNo, String name, String shortName, String contactName,
                             String contactPhone, String remark, String status, boolean fromPlatform) {
        static SupplierVO of(InvSupplier e) {
            return new SupplierVO(e.getSupplierNo(), e.getName(), e.getShortName(), e.getContactName(),
                    e.getContactPhone(), e.getRemark(), e.getStatus(),
                    e.getPlatformSupplierNo() != null && !e.getPlatformSupplierNo().isBlank());
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
