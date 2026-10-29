package ai.neargo.inventory.domain.service.impl;

import ai.neargo.inventory.api.InventoryError;
import ai.neargo.inventory.api.InventoryException;
import ai.neargo.inventory.domain.config.ConditionalOnInventory;
import ai.neargo.inventory.domain.entity.InvItem;
import ai.neargo.inventory.domain.entity.InvLocation;
import ai.neargo.inventory.domain.entity.InvSerial;
import ai.neargo.inventory.domain.entity.InvStockBalance;
import ai.neargo.inventory.domain.entity.InvStockEpisode;
import ai.neargo.inventory.domain.entity.InvTargetRef;
import ai.neargo.inventory.domain.mapper.InventoryMappers.BalanceMapper;
import ai.neargo.inventory.domain.mapper.InventoryMappers.ItemMapper;
import ai.neargo.inventory.domain.mapper.InventoryMappers.LocationMapper;
import ai.neargo.inventory.domain.mapper.InventoryMappers.SerialMapper;
import ai.neargo.inventory.domain.mapper.InventoryMappers.StockEpisodeMapper;
import ai.neargo.inventory.domain.mapper.InventoryMappers.TargetRefMapper;
import ai.neargo.inventory.domain.service.AuditService;
import ai.neargo.inventory.domain.service.Caller;
import ai.neargo.inventory.domain.service.ExternalTargetService;
import ai.neargo.inventory.domain.service.HostCatalogService;
import ai.neargo.inventory.domain.service.HostInboundService;
import ai.neargo.inventory.domain.service.LocationService;
import ai.neargo.inventory.domain.service.MigrationImportService;
import ai.neargo.inventory.domain.support.InvEnums;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

@ConditionalOnInventory
@Service
public class MigrationImportServiceImpl implements MigrationImportService {

    private final LocationMapper locationMapper;
    private final ItemMapper itemMapper;
    private final TargetRefMapper targetMapper;
    private final BalanceMapper balanceMapper;
    private final SerialMapper serialMapper;
    private final StockEpisodeMapper episodeMapper;
    private final LocationService locations;
    private final HostCatalogService catalog;
    private final ExternalTargetService targets;
    private final HostInboundService inbounds;
    private final AuditService audit;

    public MigrationImportServiceImpl(LocationMapper locationMapper, ItemMapper itemMapper,
                                      TargetRefMapper targetMapper, BalanceMapper balanceMapper,
                                      SerialMapper serialMapper, StockEpisodeMapper episodeMapper,
                                      LocationService locations, HostCatalogService catalog,
                                      ExternalTargetService targets, HostInboundService inbounds,
                                      AuditService audit) {
        this.locationMapper = locationMapper;
        this.itemMapper = itemMapper;
        this.targetMapper = targetMapper;
        this.balanceMapper = balanceMapper;
        this.serialMapper = serialMapper;
        this.episodeMapper = episodeMapper;
        this.locations = locations;
        this.catalog = catalog;
        this.targets = targets;
        this.inbounds = inbounds;
        this.audit = audit;
    }

    @Override
    @Transactional(transactionManager = "invTransactionManager")
    public Report run(Caller caller, Batch batch, boolean apply) {
        if (batch == null || batch.batchId() == null || batch.batchId().isBlank() || batch.batchId().length() > 32) {
            throw InventoryException.of(InventoryError.BAD_REQUEST, "batchId");
        }
        String owner = caller.ownerId();
        List<String> warehousesToCreate = new ArrayList<>();
        List<String> itemsToCreate = new ArrayList<>();
        List<String> itemsToUpdate = new ArrayList<>();
        List<String> targetsToUpsert = new ArrayList<>();
        List<String> conflicts = new ArrayList<>();

        // ① 仓库：按宿主仓库号对
        Map<String, String> locationByRef = new LinkedHashMap<>();
        for (Warehouse w : nz(batch.warehouses())) {
            InvLocation existing = locationMapper.selectOne(Wrappers.<InvLocation>lambdaQuery()
                    .eq(InvLocation::getOwnerId, owner).eq(InvLocation::getExternalRef, w.ref()));
            if (existing != null) {
                locationByRef.put(w.ref(), existing.getLocationId());
                continue;
            }
            warehousesToCreate.add(w.ref());
            if (apply) {
                String id = locations.createWarehouse(owner, w.name(), HostDocumentSupport.operator(caller));
                locationMapper.update(null, Wrappers.<InvLocation>lambdaUpdate()
                        .set(InvLocation::getExternalRef, w.ref()).set(InvLocation::getRegionRef, w.regionRef())
                        .eq(InvLocation::getOwnerId, owner).eq(InvLocation::getLocationId, id));
                locationByRef.put(w.ref(), id);
            }
        }
        // ② 物料：按宿主物料号对；名称、跟踪方式、放行策略不同的算更新
        Map<String, String> itemByRef = new LinkedHashMap<>();
        for (Item i : nz(batch.items())) {
            InvItem existing = itemMapper.selectOne(Wrappers.<InvItem>lambdaQuery()
                    .eq(InvItem::getOwnerId, owner).eq(InvItem::getExternalRef, i.ref()));
            if (existing == null) {
                itemsToCreate.add(i.ref());
            } else {
                itemByRef.put(i.ref(), existing.getItemId());
                if (differs(existing, i.spec())) {
                    itemsToUpdate.add(i.ref());
                } else {
                    continue;
                }
            }
            if (apply) {
                itemByRef.put(i.ref(), catalog.upsert(caller, i.ref(), i.spec()));
            }
        }
        // ③ 外部目标：先上级后下级（站点要挂在已有的商家下）
        List<Target> ordered = new ArrayList<>(nz(batch.targets()));
        ordered.sort((a, b) -> Boolean.compare(a.parentRef() != null, b.parentRef() != null));
        for (Target t : ordered) {
            InvTargetRef existing = targetMapper.selectOne(Wrappers.<InvTargetRef>lambdaQuery()
                    .eq(InvTargetRef::getOwnerId, owner).eq(InvTargetRef::getSystemCode, caller.systemCode())
                    .eq(InvTargetRef::getObjectType, t.type()).eq(InvTargetRef::getObjectRef, t.ref()));
            if (existing != null && Objects.equals(existing.getName(), t.name())
                    && Objects.equals(existing.getParentRef(), t.parentRef())) {
                continue;
            }
            targetsToUpsert.add(t.type() + ":" + t.ref());
            if (apply) {
                targets.upsert(caller, t.type(), t.ref(), t.name(), t.parentRef(), null,
                        existing == null ? 0L : existing.getSourceVersion());
            }
        }
        // ④ 在仓：只补不减
        List<StockDiff> stock = new ArrayList<>();
        Map<String, List<HostInboundService.Line>> linesByWarehouse = new LinkedHashMap<>();
        for (Stock s : nz(batch.stock())) {
            String locationId = locationByRef.get(s.warehouseRef());
            if (locationId == null) {
                locationId = locationMapper.selectList(Wrappers.<InvLocation>lambdaQuery()
                                .eq(InvLocation::getOwnerId, owner).eq(InvLocation::getExternalRef, s.warehouseRef()))
                        .stream().map(InvLocation::getLocationId).findFirst().orElse(null);
            }
            String itemId = itemByRef.get(s.itemRef());
            if (s.serials() != null && !s.serials().isEmpty()) {
                List<HostInboundService.Serial> toAdd = new ArrayList<>();
                for (HostInboundService.Serial serial : s.serials()) {
                    String where = inStockAt(owner, serial.serialNo());
                    if (where == null) {
                        toAdd.add(serial);
                    } else if (!where.equals(locationId)) {
                        conflicts.add(serial.serialNo() + ":in_stock_elsewhere");
                    }
                }
                if (!toAdd.isEmpty()) {
                    stock.add(new StockDiff(s.warehouseRef(), s.itemRef(), null, null,
                            toAdd.stream().map(HostInboundService.Serial::serialNo).toList()));
                    if (apply) {
                        linesByWarehouse.computeIfAbsent(s.warehouseRef(), k -> new ArrayList<>())
                                .add(new HostInboundService.Line(itemId, null, null, toAdd));
                    }
                }
            } else {
                BigDecimal current = onHand(owner, itemId, locationId);
                BigDecimal target = BigDecimal.valueOf(s.qty() == null ? 0 : s.qty());
                int cmp = target.compareTo(current);
                if (cmp < 0) {
                    conflicts.add(s.warehouseRef() + "/" + s.itemRef() + ":book_exceeds_batch");
                } else if (cmp > 0) {
                    stock.add(new StockDiff(s.warehouseRef(), s.itemRef(), current, target, List.of()));
                    if (apply) {
                        String gate = s.gateRef() == null ? "MIGRATION:" + batch.batchId() : s.gateRef();
                        linesByWarehouse.computeIfAbsent(s.warehouseRef(), k -> new ArrayList<>())
                                .add(new HostInboundService.Line(itemId, target.subtract(current).intValueExact(),
                                        gate, List.of()));
                    }
                }
            }
        }
        if (apply && !conflicts.isEmpty()) {
            // 冲突要人判断：执行时有冲突整批不动（预演照样出完整报告）
            throw InventoryException.of(InventoryError.CONFLICT, conflicts.toArray());
        }
        List<String> inboundNos = new ArrayList<>();
        for (Map.Entry<String, List<HostInboundService.Line>> e : linesByWarehouse.entrySet()) {
            String no = inbounds.create(caller, new HostInboundService.Draft(locationByRef.get(e.getKey()), "OPENING",
                    batch.batchId(), "MIGRATION", List.of("migration:" + batch.batchId()), "migration", e.getValue()));
            inbounds.post(caller, no);
            inboundNos.add(no);
        }
        Report report = new Report(batch.batchId(), apply, warehousesToCreate, itemsToCreate, itemsToUpdate,
                targetsToUpsert, stock, conflicts, inboundNos);
        if (apply) {
            audit.record(caller, "migration.apply", "MIGRATION", batch.batchId(), null, report, null);
        }
        return report;
    }

    private String inStockAt(String ownerId, String serialNo) {
        InvSerial serial = serialMapper.selectOne(Wrappers.<InvSerial>lambdaQuery()
                .eq(InvSerial::getOwnerId, ownerId).eq(InvSerial::getSerialNo, serialNo));
        if (serial == null || serial.getActiveEpisodeId() == null) {
            return null;
        }
        InvStockEpisode episode = episodeMapper.selectOne(Wrappers.<InvStockEpisode>lambdaQuery()
                .eq(InvStockEpisode::getOwnerId, ownerId).eq(InvStockEpisode::getEpisodeId, serial.getActiveEpisodeId()));
        return episode == null ? null : episode.getLocationId();
    }

    private BigDecimal onHand(String ownerId, String itemId, String locationId) {
        if (itemId == null || locationId == null) {
            return BigDecimal.ZERO;
        }
        InvStockBalance b = balanceMapper.selectOne(Wrappers.<InvStockBalance>lambdaQuery()
                .eq(InvStockBalance::getOwnerId, ownerId).eq(InvStockBalance::getItemId, itemId)
                .eq(InvStockBalance::getLocationId, locationId));
        return b == null || b.getOnHandQuantity() == null ? BigDecimal.ZERO : b.getOnHandQuantity();
    }

    private static boolean differs(InvItem item, HostCatalogService.ItemSpec spec) {
        String tracking = spec.trackingMode() == null ? InvEnums.TrackingMode.QUANTITY : spec.trackingMode();
        String gate = spec.gatePolicy() == null ? InvEnums.GatePolicy.NONE : spec.gatePolicy();
        return !Objects.equals(item.getName(), spec.name()) || !Objects.equals(item.getTrackingMode(), tracking)
                || !Objects.equals(item.getGatePolicy(), gate)
                || InvEnums.GatePolicy.REF_REQUIRED.equals(gate) && !Objects.equals(item.getGateRefType(),
                spec.gateRefType());
    }

    private static <T> List<T> nz(List<T> list) {
        return list == null ? List.of() : list;
    }
}
