package ai.neargo.inventory.domain.service.impl;

import ai.neargo.inventory.api.InventoryError;
import ai.neargo.inventory.api.InventoryException;
import ai.neargo.inventory.api.profile.BusinessProfile;
import ai.neargo.inventory.api.profile.Capability;
import ai.neargo.inventory.domain.config.ConditionalOnInventory;
import ai.neargo.inventory.domain.entity.InvItem;
import ai.neargo.inventory.domain.entity.InvLedger;
import ai.neargo.inventory.domain.mapper.InventoryMappers.ItemMapper;
import ai.neargo.inventory.domain.mapper.InventoryMappers.LedgerMapper;
import ai.neargo.inventory.domain.service.AuditService;
import ai.neargo.inventory.domain.service.BusinessProfileService;
import ai.neargo.inventory.domain.service.Caller;
import ai.neargo.inventory.domain.service.HostCatalogService;
import ai.neargo.inventory.domain.support.InvEnums;
import ai.neargo.inventory.domain.support.InvKeys;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Objects;

@ConditionalOnInventory
@Service
public class HostCatalogServiceImpl implements HostCatalogService {

    private final ItemMapper itemMapper;
    private final LedgerMapper ledgerMapper;
    private final BusinessProfileService profiles;
    private final AuditService audit;

    public HostCatalogServiceImpl(ItemMapper itemMapper, LedgerMapper ledgerMapper,
                                  BusinessProfileService profiles, AuditService audit) {
        this.itemMapper = itemMapper;
        this.ledgerMapper = ledgerMapper;
        this.profiles = profiles;
        this.audit = audit;
    }

    @Override
    @Transactional(transactionManager = "invTransactionManager")
    public String upsert(Caller caller, String externalRef, ItemSpec spec) {
        if (externalRef == null || externalRef.isBlank() || externalRef.length() > 64
                || spec == null || spec.name() == null || spec.name().isBlank() || spec.name().length() > 128) {
            throw InventoryException.of(InventoryError.BAD_REQUEST);
        }
        String tracking = spec.trackingMode() == null ? InvEnums.TrackingMode.QUANTITY : spec.trackingMode();
        String gate = spec.gatePolicy() == null ? InvEnums.GatePolicy.NONE : spec.gatePolicy();
        String uom = spec.baseUom() == null ? "PIECE" : spec.baseUom();
        BusinessProfile profile = profiles.current(caller.ownerId())
                .orElseThrow(() -> InventoryException.of(InventoryError.CAPABILITY_DISABLED, "profile"))
                .profile();
        checkTracking(profile, tracking);
        checkGate(profile, gate, spec.gateRefType());

        InvItem item = itemMapper.selectOne(Wrappers.<InvItem>lambdaQuery()
                .eq(InvItem::getOwnerId, caller.ownerId())
                .eq(InvItem::getExternalRef, externalRef)
                .last("FOR UPDATE"));
        String operator = caller.actor() == null ? caller.systemCode() : caller.actor();
        if (item == null) {
            item = new InvItem();
            item.setItemId(InvKeys.next(InvKeys.ITEM));
            item.setOwnerId(caller.ownerId());
            item.setExternalRef(externalRef);
            item.setDataSource(InvEnums.DataSource.SYNCED);
            item.setStatus(InvEnums.MasterStatus.ACTIVE);
            item.setCreatedBy(operator);
            apply(item, spec, tracking, gate, uom);
            itemMapper.insert(item);
            audit.record(caller, "item.create", "ITEM", item.getItemId(), null, spec, null);
            return item.getItemId();
        }
        if (hasLedger(caller.ownerId(), item.getItemId())
                && (!Objects.equals(item.getTrackingMode(), tracking) || !Objects.equals(item.getBaseUom(), uom))) {
            // 有流水之后改跟踪方式或单位：历史数字一个不变而含义全变
            throw InventoryException.of(InventoryError.CONFLICT, "trackingMode/baseUom");
        }
        ItemSpec before = new ItemSpec(item.getName(), item.getBaseUom(), item.getTrackingMode(),
                item.getGatePolicy(), item.getGateRefType(), item.getAttributes());
        apply(item, spec, tracking, gate, uom);
        item.setUpdatedBy(operator);
        itemMapper.updateById(item);
        audit.record(caller, "item.update", "ITEM", item.getItemId(), before, spec, null);
        return item.getItemId();
    }

    @Override
    public List<InvItem> list(String ownerId) {
        return itemMapper.selectList(Wrappers.<InvItem>lambdaQuery()
                .eq(InvItem::getOwnerId, ownerId)
                .orderByAsc(InvItem::getId));
    }

    private static void apply(InvItem item, ItemSpec spec, String tracking, String gate, String uom) {
        item.setName(spec.name());
        item.setBaseUom(uom);
        item.setTrackingMode(tracking);
        item.setGatePolicy(gate);
        item.setGateRefType(InvEnums.GatePolicy.REF_REQUIRED.equals(gate) ? spec.gateRefType() : null);
        item.setAttributes(spec.attributes());
    }

    private static void checkTracking(BusinessProfile profile, String tracking) {
        if (InvEnums.TrackingMode.QUANTITY.equals(tracking)) {
            return;
        }
        if (!InvEnums.TrackingMode.SERIAL.equals(tracking)) {
            // BATCH 保留不实现（批次效期单独立项）
            throw InventoryException.of(InventoryError.BAD_REQUEST, "trackingMode");
        }
        if (profile.capabilities() == null || !profile.capabilities().contains(Capability.SERIAL)) {
            throw InventoryException.of(InventoryError.CAPABILITY_DISABLED, Capability.SERIAL.name());
        }
    }

    private static void checkGate(BusinessProfile profile, String gate, String gateRefType) {
        if (InvEnums.GatePolicy.NONE.equals(gate)) {
            return;
        }
        if (!InvEnums.GatePolicy.REF_REQUIRED.equals(gate) || gateRefType == null
                || profile.gateRefTypes() == null || !profile.gateRefTypes().contains(gateRefType)) {
            throw InventoryException.of(InventoryError.BAD_REQUEST, "gateRefType");
        }
    }

    private boolean hasLedger(String ownerId, String itemId) {
        return ledgerMapper.exists(Wrappers.<InvLedger>lambdaQuery()
                .eq(InvLedger::getOwnerId, ownerId)
                .eq(InvLedger::getItemId, itemId));
    }
}
