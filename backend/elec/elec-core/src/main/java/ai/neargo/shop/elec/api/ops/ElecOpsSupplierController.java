package ai.neargo.shop.elec.api.ops;

import ai.neargo.elec.api.ElecInternal;
import ai.neargo.shop.elec.config.ConditionalOnElec;
import ai.neargo.shop.elec.dto.OpsDtos.OpsSupplierDetail;
import ai.neargo.shop.elec.dto.OpsDtos.OpsSupplierRow;
import ai.neargo.shop.elec.dto.OpsDtos.SuspendReq;
import ai.neargo.shop.elec.dto.SupplierDtos.BatchSummary;
import ai.neargo.shop.elec.dto.SupplierDtos.RegisterReq;
import ai.neargo.shop.elec.dto.SupplierDtos.StockView;
import ai.neargo.shop.elec.service.ElecOpsSupplierService;
import ai.neargo.shop.elec.service.ElecStockBatchService;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;

/**
 * 运营端 · 元器件供应商：列表、详情（含他的库存）、改资料、暂停与恢复。
 *
 * <p>看：{@code elec:supplier:read}；改与暂停恢复：{@code elec:supplier:manage}。
 */
@ConditionalOnElec
@RestController
public class ElecOpsSupplierController {

    private final ElecOpsSupplierService suppliers;
    private final ElecStockBatchService batches;

    public ElecOpsSupplierController(ElecOpsSupplierService suppliers, ElecStockBatchService batches) {
        this.suppliers = suppliers;
        this.batches = batches;
    }

    /**
     * @param keyword 公司名包含 / 电话开头 / 供应商号或匿名代号等于
     * @param status  ACTIVE / SUSPENDED；不传 = 全部
     */
    @GetMapping("/elec/ops/supplier")
    public List<OpsSupplierRow> list(@RequestParam(required = false) String keyword,
                                     @RequestParam(required = false) String status,
                                     @RequestParam(defaultValue = "1") int page,
                                     @RequestParam(defaultValue = "20") int size) {
        ElecOpsGuard.require(ElecInternal.PERM_SUPPLIER_READ);
        return suppliers.list(keyword, status, page, size);
    }

    @GetMapping("/elec/ops/supplier/{supplierNo}")
    public OpsSupplierDetail detail(@PathVariable String supplierNo) {
        ElecOpsGuard.require(ElecInternal.PERM_SUPPLIER_READ);
        return suppliers.detail(supplierNo);
    }

    /** @param filter ALL / EXPIRING（7 天内到期）/ EXPIRED */
    @GetMapping("/elec/ops/supplier/{supplierNo}/stock")
    public List<StockView> stocks(@PathVariable String supplierNo,
                                  @RequestParam(defaultValue = "") String keyword,
                                  @RequestParam(defaultValue = "ALL") String filter,
                                  @RequestParam(defaultValue = "1") int page,
                                  @RequestParam(defaultValue = "20") int size) {
        ElecOpsGuard.require(ElecInternal.PERM_SUPPLIER_READ);
        return suppliers.stocks(supplierNo, keyword, filter, page, size);
    }

    /** 代改资料：空字段 = 不改。与供应商自己改同一套校验 */
    @PutMapping("/elec/ops/supplier/{supplierNo}")
    public OpsSupplierDetail update(@PathVariable String supplierNo,
                                    @RequestBody(required = false) RegisterReq req) {
        return suppliers.update(ElecOpsGuard.require(ElecInternal.PERM_SUPPLIER_MANAGE), supplierNo, req);
    }

    /** 暂停：他的货当场不再给买家看，他也不能再上传与报价。理由必填 */
    @PostMapping("/elec/ops/supplier/{supplierNo}/suspend")
    public OpsSupplierDetail suspend(@PathVariable String supplierNo,
                                     @RequestBody(required = false) SuspendReq req) {
        return suppliers.suspend(ElecOpsGuard.require(ElecInternal.PERM_SUPPLIER_MANAGE), supplierNo,
                req == null ? null : req.reason());
    }

    @PostMapping("/elec/ops/supplier/{supplierNo}/resume")
    public OpsSupplierDetail resume(@PathVariable String supplierNo) {
        return suppliers.resume(ElecOpsGuard.require(ElecInternal.PERM_SUPPLIER_MANAGE), supplierNo);
    }

    /** 这家的上传记录（含解析失败、放弃、作废的），新的在前 */
    @GetMapping("/elec/ops/supplier/{supplierNo}/batch")
    public List<BatchSummary> batches(@PathVariable String supplierNo,
                                      @RequestParam(defaultValue = "1") int page,
                                      @RequestParam(defaultValue = "20") int size) {
        ElecOpsGuard.require(ElecInternal.PERM_SUPPLIER_READ);
        return batches.ofSupplier(supplierNo, page, size);
    }

    /**
     * 下载某次上传的原件，文件名是供应商传上来时的原名（RFC 5987 的 filename*，中文名不乱码）。
     * 原件已被清理回 90018。直接写响应：返回字节会被全局信封包住
     */
    @GetMapping("/elec/ops/supplier/{supplierNo}/batch/{batchNo}/file")
    public void original(@PathVariable String supplierNo, @PathVariable String batchNo, HttpServletResponse resp)
            throws IOException {
        ElecOpsGuard.require(ElecInternal.PERM_SUPPLIER_READ);
        ElecStockBatchService.OriginalFile f = batches.original(supplierNo, batchNo);
        resp.setContentType(f.contentType());
        resp.setHeader(HttpHeaders.CONTENT_DISPOSITION,
                ContentDisposition.attachment().filename(f.name(), StandardCharsets.UTF_8).build().toString());
        resp.setContentLengthLong(Files.size(f.path()));
        Files.copy(f.path(), resp.getOutputStream());
    }
}
