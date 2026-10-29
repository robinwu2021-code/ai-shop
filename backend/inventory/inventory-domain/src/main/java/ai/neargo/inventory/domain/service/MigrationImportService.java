package ai.neargo.inventory.domain.service;

import java.math.BigDecimal;
import java.util.List;

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

    record Batch(String batchId, List<Warehouse> warehouses, List<Item> items, List<Target> targets,
                 List<Stock> stock) {
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

    record Report(String batchId, boolean applied, List<String> warehousesToCreate, List<String> itemsToCreate,
                  List<String> itemsToUpdate, List<String> targetsToUpsert, List<StockDiff> stock,
                  List<String> conflicts, List<String> inboundNos) {

        public boolean empty() {
            return warehousesToCreate.isEmpty() && itemsToCreate.isEmpty() && itemsToUpdate.isEmpty()
                    && targetsToUpsert.isEmpty() && stock.isEmpty();
        }
    }

    record StockDiff(String warehouseRef, String itemRef, BigDecimal current, BigDecimal target,
                     List<String> serialsToAdd) {
    }
}
