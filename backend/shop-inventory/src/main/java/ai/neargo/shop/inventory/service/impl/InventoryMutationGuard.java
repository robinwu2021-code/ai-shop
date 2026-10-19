package ai.neargo.shop.inventory.service.impl;

import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.inventory.config.ConditionalOnInventory;
import ai.neargo.shop.inventory.entity.InvStockCount;
import ai.neargo.shop.inventory.entity.InvStockCountLine;
import ai.neargo.shop.inventory.entity.InvLocation;
import ai.neargo.shop.inventory.mapper.InventoryMappers.LocationMapper;
import ai.neargo.shop.inventory.mapper.InventoryMappers.StockCountMapper;
import ai.neargo.shop.inventory.mapper.InventoryMappers.StockCountLineMapper;
import ai.neargo.shop.inventory.support.InvEnums;
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
        throw BizException.of(ErrorCode.CONFLICT);
    }

    /** Shared serialization point for opening a count snapshot and posting physical stock changes. */
    void lockLocation(String ownerId, String locationId) {
        InvLocation location = locations.selectOne(Wrappers.<InvLocation>lambdaQuery()
                .eq(InvLocation::getOwnerId, ownerId).eq(InvLocation::getLocationId, locationId)
                .last("FOR UPDATE"));
        if (location == null) throw BizException.of(ErrorCode.NOT_FOUND);
    }
}
