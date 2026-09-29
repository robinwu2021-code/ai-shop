package ai.neargo.shop.elec.service;

import ai.neargo.shop.elec.dto.OpsDtos.OpsSupplierDetail;
import ai.neargo.shop.elec.dto.OpsDtos.OpsSupplierRow;
import ai.neargo.shop.elec.dto.SupplierDtos.RegisterReq;
import ai.neargo.shop.elec.dto.SupplierDtos.StockView;

import java.util.List;

/** 运营端 · 供应商：看、改、暂停与恢复。 */
public interface ElecOpsSupplierService {

    /**
     * @param keyword 公司名包含 / 电话开头 / 供应商号或匿名代号等于；空 = 全部
     * @param status  ACTIVE / SUSPENDED；空 = 全部
     */
    List<OpsSupplierRow> list(String keyword, String status, int page, int size);

    OpsSupplierDetail detail(String supplierNo);

    /** 他的在售库存。@param filter ALL / EXPIRING / EXPIRED */
    List<StockView> stocks(String supplierNo, String keyword, String filter, int page, int size);

    /** 代改资料。与供应商自己改同一套校验；暂停中的也能改 */
    OpsSupplierDetail update(String staffNo, String supplierNo, RegisterReq req);

    /**
     * 暂停：他的货<b>当场</b>不再给买家看（重算他全部料号的买家面），他也不能再上传、报价。
     * 已暂停的再暂停一次 = 改理由。
     *
     * @param reason 至少 2 个字
     */
    OpsSupplierDetail suspend(String staffNo, String supplierNo, String reason);

    /** 恢复：他的货重新给买家看。已是正常状态的 = 什么都不做 */
    OpsSupplierDetail resume(String staffNo, String supplierNo);
}
