package ai.neargo.shop.inventory.api.biz;

import ai.neargo.shop.inventory.config.ConditionalOnInventory;
import ai.neargo.shop.auth.BizContext;
import ai.neargo.shop.auth.BizPerms;
import ai.neargo.shop.inventory.dto.InventoryVOs.DocumentVO;
import ai.neargo.shop.inventory.dto.InventoryVOs.MonthlyVO;
import ai.neargo.shop.inventory.dto.InventoryVOs.RankVO;
import ai.neargo.shop.inventory.service.InventoryAclService;
import ai.neargo.shop.inventory.service.InventoryReportService;
import ai.neargo.shop.inventory.service.LocationService;
import ai.neargo.shop.inventory.service.StockQueryService;
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

    public BizStockReportController(StockQueryService query, InventoryReportService reports,
                                    InventoryAclService acl, LocationService locations) {
        this.query = query;
        this.reports = reports;
        this.acl = acl;
        this.locations = locations;
    }

    @PreAuthorize("@perm.canBiz('" + BizPerms.STOCK + "')")
    @GetMapping("/biz/inventory/documents")
    public List<DocumentVO> documents(@RequestParam(required = false) String kind,
                                      @RequestParam(defaultValue = "30") int size) {
        return query.documents(owner(), location(), kind, Math.min(size, PAGE_MAX));
    }

    @PreAuthorize("@perm.canBiz('" + BizPerms.CUSTOMER + "')")
    @GetMapping("/biz/inventory/report/monthly")
    public MonthlyVO monthly(@RequestParam String month) {
        return reports.monthly(owner(), location(), month);
    }

    @PreAuthorize("@perm.canBiz('" + BizPerms.CUSTOMER + "')")
    @GetMapping("/biz/inventory/report/ranking")
    public List<RankVO> ranking(@RequestParam(defaultValue = "fast") String type,
                                @RequestParam(defaultValue = "30") int days,
                                @RequestParam(defaultValue = "10") int limit) {
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
        byte[] body = reports.exportCsv(owner(), location(), type).getBytes(StandardCharsets.UTF_8);
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
