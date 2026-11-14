package ai.neargo.inventory.domain.service.impl;

import ai.neargo.inventory.api.InventoryError;
import ai.neargo.inventory.api.InventoryException;
import ai.neargo.inventory.domain.config.ConditionalOnInventory;
import ai.neargo.inventory.domain.entity.InvStockCount;
import ai.neargo.inventory.domain.entity.InvStockCountLine;
import ai.neargo.inventory.domain.mapper.InventoryMappers.StockCountLineMapper;
import ai.neargo.inventory.domain.mapper.InventoryMappers.StockCountMapper;
import ai.neargo.inventory.domain.support.InvEnums;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.List;

/**
 * 盘点范围冻结（TDD §2.10，AC-11）：开着的盘点单就是冻结，持久化在库里，重启照样在。
 *
 * <p>范围 = 盘点行上的物料。宿主所有改库存的入口（入库过账、出库过账、撤回）在动余额之前先问这里；
 * 盘点自己生成的盘盈 / 盘亏单豁免。先锁库位行：与开盘同一把锁，开盘和过账不会同时发生。
 */
@ConditionalOnInventory
@Component
class CountFreezeGuard {

    /** 冻结中的状态：录数中、待复核、已批准未过账。商城盘点只有「录数中」。 */
    static final List<String> OPEN = List.of(InvEnums.DocStatus.COUNTING, "SUBMITTED", "APPROVED");

    private final StockCountMapper counts;
    private final StockCountLineMapper lines;
    private final InventoryMutationGuard locations;

    CountFreezeGuard(StockCountMapper counts, StockCountLineMapper lines, InventoryMutationGuard locations) {
        this.counts = counts;
        this.lines = lines;
        this.locations = locations;
    }

    /** @param exemptCountNo 盘点自己的盈亏单带上它的单号，不被自己挡住 */
    void assertNotFrozen(String ownerId, String locationId, Collection<String> itemIds, String exemptCountNo) {
        locations.lockLocation(ownerId, locationId);
        if (itemIds.isEmpty()) {
            return;
        }
        for (InvStockCount open : counts.selectList(Wrappers.<InvStockCount>lambdaQuery()
                .eq(InvStockCount::getOwnerId, ownerId)
                .eq(InvStockCount::getLocationId, locationId)
                .in(InvStockCount::getStatus, OPEN))) {
            if (open.getCountNo().equals(exemptCountNo)) {
                continue;
            }
            boolean inScope = lines.exists(Wrappers.<InvStockCountLine>lambdaQuery()
                    .eq(InvStockCountLine::getOwnerId, ownerId)
                    .eq(InvStockCountLine::getCountNo, open.getCountNo())
                    .in(InvStockCountLine::getItemId, itemIds));
            if (inScope) {
                throw InventoryException.of(InventoryError.COUNT_FROZEN, open.getCountNo());
            }
        }
    }
}
