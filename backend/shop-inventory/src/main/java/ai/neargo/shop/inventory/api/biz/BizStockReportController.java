package ai.neargo.shop.inventory.api.biz;

import ai.neargo.shop.inventory.config.ConditionalOnInventory;
import ai.neargo.shop.auth.BizContext;
import ai.neargo.shop.auth.BizPerms;
import ai.neargo.inventory.domain.dto.InventoryVOs.DocumentVO;
import ai.neargo.inventory.domain.dto.InventoryVOs.MonthlyVO;
import ai.neargo.inventory.domain.dto.InventoryVOs.RankVO;
import ai.neargo.inventory.domain.service.InventoryAclService;
import ai.neargo.inventory.domain.service.InventoryReportService;
import ai.neargo.inventory.domain.service.LocationService;
import ai.neargo.inventory.domain.service.StockQueryService;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.util.List;
import ai.neargo.shop.inventory.route.InventoryConsoleRoute;
import static ai.neargo.inventory.client.InventoryConsole.params;

/**
 * 商家端 · 单据列表与报表（s05 · s08）。
 *
 * <p><b>报表用 {@code biz:customer} 而不是 {@code biz:stock}</b>：
 * 那个码的语义是「顾客列表（含累计消费额）、经营数据 —— 客户资产」。
 * 店员有 {@code biz:stock}（改库存是他每天的活），但**不该看得到毛利与销量**。
 *
 * <p>毛利不在这里算：本域只出销量与**销货成本**，售价在销售域。
 */
@Profile("api")
@ConditionalOnInventory
@RestController
public class BizStockReportController {

    private static final int PAGE_MAX = 100;

    private final StockQueryService query;
    private final InventoryReportService reports;
    private final InventoryAclService acl;
    private final LocationService locations;

    /** 切流（M8）：已翻到库存服务的商家，转给服务的 /console，返回结构相同 */
    private final InventoryConsoleRoute route;

    public BizStockReportController(StockQueryService query, InventoryReportService reports,
                                    InventoryAclService acl, LocationService locations,
                                    InventoryConsoleRoute route) {
        this.query = query;
        this.reports = reports;
        this.acl = acl;
        this.locations = locations;
        this.route = route;
    }

    @PreAuthorize("@perm.canBiz('" + BizPerms.STOCK + "')")
    @GetMapping("/biz/inventory/documents")
    public List<DocumentVO> documents(@RequestParam(required = false) String kind,
                                      @RequestParam(required = false) String no,
                                      @RequestParam(defaultValue = "30") int size) {
        if (route.remote()) return route.via(c -> c.get("/documents", params("kind", kind, "no", no, "storeRef", route.store(),
                "size", size), new tools.jackson.core.type.TypeReference<List<DocumentVO>>() { }));
        return query.documents(owner(), location(), kind, no, Math.min(size, PAGE_MAX));
    }

    @PreAuthorize("@perm.canBiz('" + BizPerms.CUSTOMER + "')")
    @GetMapping("/biz/inventory/report/monthly")
    public MonthlyVO monthly(@RequestParam String month) {
        if (route.remote()) return route.via(c -> c.get("/report/monthly", params("month", month, "storeRef", route.store()), MonthlyVO.class));
        return reports.monthly(owner(), location(), month);
    }

    @PreAuthorize("@perm.canBiz('" + BizPerms.CUSTOMER + "')")
    @GetMapping("/biz/inventory/report/ranking")
    public List<RankVO> ranking(@RequestParam(defaultValue = "fast") String type,
                                @RequestParam(defaultValue = "30") int days,
                                @RequestParam(defaultValue = "10") int limit) {
        if (route.remote()) return route.via(c -> c.get("/report/ranking", params("type", type, "days", days, "limit", limit,
                "storeRef", route.store()), new tools.jackson.core.type.TypeReference<List<RankVO>>() { }));
        return reports.ranking(owner(), location(), type, days, Math.min(limit, PAGE_MAX));
    }

    /**
     * 导出 CSV。
     *
     * <p>审计**由 portal 层记**（{@code sys_audit_log} 在平台库，本域不认识它）——
     * 跨库写审计等于把平台的表拉进本域的事务。
     */
    @PreAuthorize("@perm.canBiz('" + BizPerms.CUSTOMER + "')")
    @GetMapping("/biz/inventory/export")
    public ResponseEntity<byte[]> export(@RequestParam(defaultValue = "ledger") String type) {
        String csv = route.remote()
                ? route.via(c -> c.getText("/export", params("type", type, "storeRef", route.store())))
                : reports.exportCsv(owner(), location(), type);
        byte[] body = (csv == null ? "" : csv).getBytes(StandardCharsets.UTF_8);

        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=\"inventory-" + type + ".csv\"")
                .contentType(new MediaType("text", "csv", StandardCharsets.UTF_8))
                .body(body);
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
