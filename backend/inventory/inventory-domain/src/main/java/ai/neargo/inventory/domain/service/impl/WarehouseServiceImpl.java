package ai.neargo.inventory.domain.service.impl;

import ai.neargo.inventory.api.InventoryError;
import ai.neargo.inventory.api.InventoryException;
import ai.neargo.inventory.domain.config.ConditionalOnInventory;
import ai.neargo.inventory.domain.entity.InvInboundOrder;
import ai.neargo.inventory.domain.entity.InvLocation;
import ai.neargo.inventory.domain.entity.InvOutboundOrder;
import ai.neargo.inventory.domain.entity.InvStockBalance;
import ai.neargo.inventory.domain.entity.InvStockCount;
import ai.neargo.inventory.domain.entity.InvStockHold;
import ai.neargo.inventory.domain.entity.InvTransferOrder;
import ai.neargo.inventory.domain.mapper.InventoryMappers.BalanceMapper;
import ai.neargo.inventory.domain.mapper.InventoryMappers.InboundOrderMapper;
import ai.neargo.inventory.domain.mapper.InventoryMappers.LocationMapper;
import ai.neargo.inventory.domain.mapper.InventoryMappers.OutboundOrderMapper;
import ai.neargo.inventory.domain.mapper.InventoryMappers.StockCountMapper;
import ai.neargo.inventory.domain.mapper.InventoryMappers.StockHoldMapper;
import ai.neargo.inventory.domain.mapper.InventoryMappers.TransferOrderMapper;
import ai.neargo.inventory.domain.service.AuditService;
import ai.neargo.inventory.domain.service.Caller;
import ai.neargo.inventory.domain.service.WarehouseService;
import ai.neargo.inventory.domain.support.InvEnums;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@ConditionalOnInventory
@Service
public class WarehouseServiceImpl implements WarehouseService {

    private final LocationMapper locationMapper;
    private final BalanceMapper balanceMapper;
    private final InboundOrderMapper inboundMapper;
    private final OutboundOrderMapper outboundMapper;
    private final StockCountMapper countMapper;
    private final TransferOrderMapper transferMapper;
    private final StockHoldMapper holdMapper;
    private final AuditService audit;

    public WarehouseServiceImpl(LocationMapper locationMapper, BalanceMapper balanceMapper,
                                InboundOrderMapper inboundMapper, OutboundOrderMapper outboundMapper,
                                StockCountMapper countMapper, TransferOrderMapper transferMapper,
                                StockHoldMapper holdMapper, AuditService audit) {
        this.locationMapper = locationMapper;
        this.balanceMapper = balanceMapper;
        this.inboundMapper = inboundMapper;
        this.outboundMapper = outboundMapper;
        this.countMapper = countMapper;
        this.transferMapper = transferMapper;
        this.holdMapper = holdMapper;
        this.audit = audit;
    }

    @Override
    @Transactional(transactionManager = "invTransactionManager")
    public void update(Caller caller, String locationId, String name, String regionRef) {
        InvLocation location = warehouse(caller.ownerId(), locationId);
        if (name != null && (name.isBlank() || name.length() > 128)
                || regionRef != null && regionRef.length() > 64) {
            throw InventoryException.of(InventoryError.BAD_REQUEST);
        }
        String before = location.getName() + "|" + location.getRegionRef();
        if (name != null) {
            location.setName(name);
        }
        location.setRegionRef(regionRef);
        location.setUpdatedBy(caller.actor());
        locationMapper.updateById(location);
        audit.record(caller, "warehouse.update", "WAREHOUSE", locationId, before,
                location.getName() + "|" + location.getRegionRef(), null);
    }

    @Override
    @Transactional(transactionManager = "invTransactionManager")
    public void disable(Caller caller, String locationId) {
        InvLocation location = warehouse(caller.ownerId(), locationId);
        if (InvEnums.MasterStatus.DISABLED.equals(location.getStatus())) {
            return;
        }
        String owner = caller.ownerId();
        List<String> reasons = new ArrayList<>();
        boolean balance = balanceMapper.selectList(Wrappers.<InvStockBalance>lambdaQuery()
                        .eq(InvStockBalance::getOwnerId, owner).eq(InvStockBalance::getLocationId, locationId))
                .stream().anyMatch(b -> nonZero(b.getOnHandQuantity()) || nonZero(b.getReservedQuantity())
                        || nonZero(b.getUnavailableQuantity()));
        if (balance) {
            reasons.add("BALANCE");
        }
        boolean openDocs = inboundMapper.exists(Wrappers.<InvInboundOrder>lambdaQuery()
                        .eq(InvInboundOrder::getOwnerId, owner).eq(InvInboundOrder::getLocationId, locationId)
                        .eq(InvInboundOrder::getStatus, InvEnums.DocStatus.DRAFT))
                || outboundMapper.exists(Wrappers.<InvOutboundOrder>lambdaQuery()
                        .eq(InvOutboundOrder::getOwnerId, owner).eq(InvOutboundOrder::getLocationId, locationId)
                        .eq(InvOutboundOrder::getStatus, InvEnums.DocStatus.DRAFT))
                || countMapper.exists(Wrappers.<InvStockCount>lambdaQuery()
                        .eq(InvStockCount::getOwnerId, owner).eq(InvStockCount::getLocationId, locationId)
                        .eq(InvStockCount::getStatus, InvEnums.DocStatus.COUNTING))
                || transferMapper.exists(Wrappers.<InvTransferOrder>lambdaQuery()
                        .eq(InvTransferOrder::getOwnerId, owner)
                        .in(InvTransferOrder::getStatus, InvEnums.TransferStatus.DRAFT, InvEnums.TransferStatus.SHIPPED)
                        .and(w -> w.eq(InvTransferOrder::getFromLocationId, locationId)
                                .or().eq(InvTransferOrder::getToLocationId, locationId)));
        if (openDocs) {
            reasons.add("OPEN_DOCUMENTS");
        }
        if (holdMapper.exists(Wrappers.<InvStockHold>lambdaQuery()
                .eq(InvStockHold::getOwnerId, owner).eq(InvStockHold::getLocationId, locationId)
                .eq(InvStockHold::getStatus, InvEnums.HoldStatus.ACTIVE))) {
            reasons.add("HOLDS");
        }
        if (!reasons.isEmpty()) {
            throw InventoryException.of(InventoryError.CONFLICT, reasons.toArray());
        }
        location.setStatus(InvEnums.MasterStatus.DISABLED);
        location.setDisabledAt(LocalDateTime.now());
        location.setUpdatedBy(caller.actor());
        locationMapper.updateById(location);
        audit.record(caller, "warehouse.disable", "WAREHOUSE", locationId, null, null, null);
    }

    private InvLocation warehouse(String ownerId, String locationId) {
        InvLocation location = locationMapper.selectOne(Wrappers.<InvLocation>lambdaQuery()
                .eq(InvLocation::getOwnerId, ownerId)
                .eq(InvLocation::getLocationId, locationId)
                .eq(InvLocation::getKind, InvEnums.LocationKind.WAREHOUSE)
                .last("FOR UPDATE"));
        if (location == null) {
            throw InventoryException.of(InventoryError.NOT_FOUND, "warehouse");
        }
        return location;
    }

    private static boolean nonZero(BigDecimal value) {
        return value != null && value.signum() != 0;
    }
}
