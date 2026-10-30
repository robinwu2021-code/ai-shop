package ai.neargo.inventory.domain.service;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/**
 * 宿主旧仓储数据导入（TDD §2.18 T6.1、§7.2 Q3）：同一份批次先预演、出差异报告，确认后再执行；
 * <b>可重复执行</b> —— 第二次执行时差异为空，什么都不会再建。
 *
 * <p>仓内设备按期初入库（{@code OPENING}，依据 {@code MIGRATION}，附件 {@code migration:<批次号>}）；
 * 已投放、不在仓的设备不导入。<b>只补不减</b>：账面多于批次时不自动盘亏，列为冲突交给人。
 */
public interface MigrationImportService {

    /** @param apply false = 只预演；true = 执行（全部在一个事务里，任一步失败整批回滚） */
    Report run(Caller caller, Batch batch, boolean apply);

    /** @param pool 已发出、对方还没签收的件（TDD §2.21）：入库后立即按交接用途出库进池，不领取 */
    record Batch(String batchId, List<Warehouse> warehouses, List<Item> items, List<Target> targets,
                 List<Stock> stock, List<Pool> pool) {
    }

    /** @param ref 宿主仓库号（落在 {@code inv_location.external_ref}） */
    record Warehouse(String ref, String name, String regionRef) {
    }

    record Item(String ref, HostCatalogService.ItemSpec spec) {
    }

    record Target(String type, String ref, String name, String parentRef) {
    }

    /**
     * 一个仓里一个物料的在仓情况。逐件物料给 {@code serials}；数量物料给 {@code qty}。
     *
     * @param gateRef 数量物料的放行依据；空 = {@code MIGRATION:<批次号>}
     */
    record Stock(String warehouseRef, String itemRef, Integer qty, String gateRef,
                 List<HostInboundService.Serial> serials) {
    }

    /**
     * 一张发货单上已发出未签收的件（只收 SN 物料：数量行的池记录没有可对账的业务键）。
     *
     * @param purpose 交接出库用途；空 = {@code ISSUE}
     * @param targets 出库对象 {类型: 编号}，与宿主建发货单时一致；对象须已同步或在本批 {@code targets} 里
     * @param serials 逐件；下级件（宝）用 {@code parentSerialNo} 指向本批里的上级件（柜），出库时随上级件带出
     */
    record Pool(String warehouseRef, String purpose, Map<String, String> targets, List<PoolSerial> serials) {
    }

    /** @param gateRef 放行凭证；空 = {@code MIGRATION:<批次号>} */
    record PoolSerial(String itemRef, String serialNo, String gateRef, String parentSerialNo) {
    }

    record Report(String batchId, boolean applied, List<String> warehousesToCreate, List<String> itemsToCreate,
                  List<String> itemsToUpdate, List<String> targetsToUpsert, List<StockDiff> stock,
                  List<String> poolToIssue, List<String> conflicts, List<String> inboundNos,
                  List<String> outboundNos) {

        public boolean empty() {
            return warehousesToCreate.isEmpty() && itemsToCreate.isEmpty() && itemsToUpdate.isEmpty()
                    && targetsToUpsert.isEmpty() && stock.isEmpty() && poolToIssue.isEmpty();
        }
    }

    record StockDiff(String warehouseRef, String itemRef, BigDecimal current, BigDecimal target,
                     List<String> serialsToAdd) {
    }
}
