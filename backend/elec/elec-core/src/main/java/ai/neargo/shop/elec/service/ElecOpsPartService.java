package ai.neargo.shop.elec.service;

import ai.neargo.shop.elec.dto.OpsDtos.OpsPartDetail;
import ai.neargo.shop.elec.dto.OpsDtos.OpsPartRow;
import ai.neargo.shop.elec.dto.OpsDtos.OpsStockRow;

import java.util.List;

/** 运营端 · 料号与库存。看得到每家的精确数量与电话 —— 买家那一侧永远走 {@link ElecPartService}。 */
public interface ElecOpsPartService {

    /** 料号搜索：与买家同一套命中，但不记需求统计。最多 50 条 */
    List<OpsPartRow> search(String q);

    /** 某料号：谁有货（真名、电话、阶梯价、起订量…），按数量倒序最多 50 家 */
    OpsPartDetail detail(String partNo);

    /**
     * 库存行查询。
     *
     * @param q          料号开头；空 = 不限
     * @param supplierNo 空 = 全部供应商
     * @param filter     ALL / EXPIRING / EXPIRED
     */
    List<OpsStockRow> stocks(String q, String supplierNo, String filter, int page, int size);
}
