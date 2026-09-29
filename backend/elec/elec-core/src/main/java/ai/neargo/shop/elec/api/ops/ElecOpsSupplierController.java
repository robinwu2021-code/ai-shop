package ai.neargo.shop.elec.api.ops;

import ai.neargo.elec.api.ElecInternal;
import ai.neargo.shop.elec.config.ConditionalOnElec;
import ai.neargo.shop.elec.dto.OpsDtos.OpsSupplierDetail;
import ai.neargo.shop.elec.dto.OpsDtos.OpsSupplierRow;
import ai.neargo.shop.elec.dto.OpsDtos.SuspendReq;
import ai.neargo.shop.elec.dto.SupplierDtos.RegisterReq;
import ai.neargo.shop.elec.dto.SupplierDtos.StockView;
import ai.neargo.shop.elec.service.ElecOpsSupplierService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

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

    public ElecOpsSupplierController(ElecOpsSupplierService suppliers) {
        this.suppliers = suppliers;
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
}
