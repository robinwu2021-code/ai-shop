package ai.neargo.inventory.domain.service.impl;

import ai.neargo.inventory.api.InventoryException;
import ai.neargo.inventory.api.InventoryError;
import ai.neargo.inventory.domain.config.ConditionalOnInventory;
import ai.neargo.inventory.domain.entity.InvStockCount;
import ai.neargo.inventory.domain.entity.InvStockCountLine;
import ai.neargo.inventory.domain.entity.InvLocation;
import ai.neargo.inventory.domain.mapper.InventoryMappers.LocationMapper;
import ai.neargo.inventory.domain.mapper.InventoryMappers.StockCountMapper;
import ai.neargo.inventory.domain.mapper.InventoryMappers.StockCountLineMapper;
import ai.neargo.inventory.domain.support.InvEnums;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.springframework.stereotype.Component;

/** Prevents physical stock mutations while a count snapshot is open. Paid allocation is non-physical and remains allowed. */
@Component
@ConditionalOnInventory
class InventoryMutationGuard {
    private final StockCountMapper counts;
    private final StockCountLineMapper lines;
    private final LocationMapper locations;

    InventoryMutationGuard(StockCountMapper counts, StockCountLineMapper lines, LocationMapper locations) {
        this.counts = counts;
        this.lines = lines;
        this.locations = locations;
    }

    void assertMutable(String ownerId, String locationId, String itemId, String countNo) {
        lockLocation(ownerId, locationId);
        InvStockCount active = counts.selectOne(Wrappers.<InvStockCount>lambdaQuery()
                .eq(InvStockCount::getOwnerId, ownerId).eq(InvStockCount::getLocationId, locationId)
                .eq(InvStockCount::getStatus, InvEnums.DocStatus.COUNTING).last("LIMIT 1"));
        if (active == null) return;
        if (active.getCountNo().equals(countNo) && lines.selectCount(Wrappers.<InvStockCountLine>lambdaQuery()
                .eq(InvStockCountLine::getCountNo, countNo).eq(InvStockCountLine::getOwnerId, ownerId)
                .eq(InvStockCountLine::getItemId, itemId)) > 0) return;
        throw InventoryException.of(InventoryError.CONFLICT);
    }

    /** Shared serialization point for opening a count snapshot and posting physical stock changes. */
    void lockLocation(String ownerId, String locationId) {
        InvLocation location = locations.selectOne(Wrappers.<InvLocation>lambdaQuery()
                .eq(InvLocation::getOwnerId, ownerId).eq(InvLocation::getLocationId, locationId)
                .last("FOR UPDATE"));
        if (location == null) throw InventoryException.of(InventoryError.NOT_FOUND);
    }
}
