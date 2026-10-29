package ai.neargo.inventory.domain.service.impl;

import ai.neargo.inventory.api.InventoryError;
import ai.neargo.inventory.api.InventoryException;
import ai.neargo.inventory.domain.config.ConditionalOnInventory;
import ai.neargo.inventory.domain.entity.InvDispatchAdjust;
import ai.neargo.inventory.domain.entity.InvDispatchClaim;
import ai.neargo.inventory.domain.entity.InvDispatchItem;
import ai.neargo.inventory.domain.entity.InvDispatchTarget;
import ai.neargo.inventory.domain.entity.InvDocumentSerial;
import ai.neargo.inventory.domain.entity.InvDocumentTarget;
import ai.neargo.inventory.domain.entity.InvOutboundLine;
import ai.neargo.inventory.domain.entity.InvOutboundOrder;
import ai.neargo.inventory.domain.entity.InvTargetRef;
import ai.neargo.inventory.domain.mapper.InventoryMappers.DispatchAdjustMapper;
import ai.neargo.inventory.domain.mapper.InventoryMappers.DispatchClaimMapper;
import ai.neargo.inventory.domain.mapper.InventoryMappers.DispatchItemMapper;
import ai.neargo.inventory.domain.mapper.InventoryMappers.DispatchTargetMapper;
import ai.neargo.inventory.domain.service.Caller;
import ai.neargo.inventory.domain.service.EventPublisher;
import ai.neargo.inventory.domain.support.InvKeys;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 出库池的存取（包内）：建记录、成套家族、改目标、标退回、发事件。
 *
 * <p>入库（退货标记）、出库（过账生成）、出库池服务（调整 / 撤回 / 签收）三方共用它，
 * 而它不依赖任何一方 —— 这样三者之间没有循环依赖。
 */
@ConditionalOnInventory
@Component
class DispatchPoolStore {

    static final String AVAILABLE = "AVAILABLE";
    static final String CLAIMED = "CLAIMED";
    static final String REVOKED = "REVOKED";
    static final String RETURNED = "RETURNED";
    static final String RESOURCE = "DISPATCH";

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final DispatchItemMapper itemMapper;
    private final DispatchTargetMapper targetMapper;
    private final DispatchClaimMapper claimMapper;
    private final DispatchAdjustMapper adjustMapper;
    private final EventPublisher events;

    DispatchPoolStore(DispatchItemMapper itemMapper, DispatchTargetMapper targetMapper,
                      DispatchClaimMapper claimMapper, DispatchAdjustMapper adjustMapper, EventPublisher events) {
        this.itemMapper = itemMapper;
        this.targetMapper = targetMapper;
        this.claimMapper = claimMapper;
        this.adjustMapper = adjustMapper;
        this.events = events;
    }

    /**
     * 交接出库过账时生成出库池：逐件一件一条，数量一行一条；下级件挂到上级件的记录下（成套）。
     *
     * @param rootType 收货方层级最顶的目标类型（用途的第一个必填目标，如商家）
     */
    void createFromIssue(Caller caller, InvOutboundOrder order, List<InvOutboundLine> lines,
                         List<InvDocumentSerial> serials, Map<String, String> episodeBySerialNo,
                         List<InvDocumentTarget> targets, String rootType) {
        InvDocumentTarget root = targets.stream().filter(t -> t.getTargetType().equals(rootType)).findFirst()
                .orElseThrow(() -> InventoryException.of(InventoryError.BAD_REQUEST, "target.required:" + rootType));
        Map<String, String> dispatchBySerialNo = new HashMap<>();
        List<InvDispatchItem> created = new ArrayList<>();
        // 先建上级件（没有 parentSerialNo 的），再建下级件，才能挂上 parent_dispatch_id
        List<InvDocumentSerial> ordered = new ArrayList<>(serials);
        ordered.sort((a, b) -> Boolean.compare(a.getParentSerialNo() != null, b.getParentSerialNo() != null));
        for (InvDocumentSerial s : ordered) {
            InvDispatchItem row = base(caller, order, s.getLineNo(), s.getItemId(), BigDecimal.ONE, root);
            row.setSerialId(s.getSerialId());
            row.setSerialNo(s.getSerialNo());
            row.setEpisodeId(episodeBySerialNo.get(s.getSerialNo()));
            row.setParentDispatchId(s.getParentSerialNo() == null ? null
                    : dispatchBySerialNo.get(s.getParentSerialNo()));
            itemMapper.insert(row);
            dispatchBySerialNo.put(s.getSerialNo(), row.getDispatchId());
            created.add(row);
        }
        for (InvOutboundLine line : lines) {
            boolean serialLine = serials.stream().anyMatch(s -> s.getLineNo().equals(line.getLineNo()));
            if (serialLine) {
                continue;
            }
            InvDispatchItem row = base(caller, order, line.getLineNo(), line.getItemId(), line.getQtyQuantity(), root);
            itemMapper.insert(row);
            created.add(row);
        }
        for (InvDispatchItem row : created) {
            for (InvDocumentTarget t : targets) {
                insertTarget(caller, row.getDispatchId(), t.getTargetType(), t.getTargetRef(), t.getNameSnapshot());
            }
            publish(caller, "DispatchCreated", row);
        }
    }

    boolean hasRecords(String ownerId, String outboundNo) {
        return itemMapper.exists(Wrappers.<InvDispatchItem>lambdaQuery()
                .eq(InvDispatchItem::getOwnerId, ownerId).eq(InvDispatchItem::getOutboundNo, outboundNo));
    }

    InvDispatchItem lock(String ownerId, String dispatchId) {
        return itemMapper.selectOne(Wrappers.<InvDispatchItem>lambdaQuery()
                .eq(InvDispatchItem::getOwnerId, ownerId).eq(InvDispatchItem::getDispatchId, dispatchId)
                .last("FOR UPDATE"));
    }

    /** 一个主记录连同它的从属记录（主记录在前）。 */
    List<InvDispatchItem> family(String ownerId, InvDispatchItem head) {
        List<InvDispatchItem> all = new ArrayList<>();
        all.add(head);
        all.addAll(itemMapper.selectList(Wrappers.<InvDispatchItem>lambdaQuery()
                .eq(InvDispatchItem::getOwnerId, ownerId)
                .eq(InvDispatchItem::getParentDispatchId, head.getDispatchId())
                .orderByAsc(InvDispatchItem::getId)
                .last("FOR UPDATE")));
        return all;
    }

    /** 只能对主记录（或独立记录）操作；从属记录随主记录走。 */
    InvDispatchItem requireHead(String ownerId, String dispatchId) {
        InvDispatchItem row = lock(ownerId, dispatchId);
        if (row == null) {
            throw InventoryException.of(InventoryError.NOT_FOUND, "dispatch:" + dispatchId);
        }
        if (row.getParentDispatchId() != null) {
            throw InventoryException.of(InventoryError.DISPATCH_CHILD_LOCKED, dispatchId);
        }
        return row;
    }

    void setStatus(Caller caller, InvDispatchItem row, String status, String eventType) {
        row.setStatus(status);
        row.setResourceVersion(row.getResourceVersion() + 1);
        row.setUpdatedBy(HostDocumentSupport.operator(caller));
        itemMapper.updateById(row);
        publish(caller, eventType, row);
    }

    Map<String, InvDispatchTarget> targets(String ownerId, String dispatchId) {
        Map<String, InvDispatchTarget> map = new LinkedHashMap<>();
        targetMapper.selectList(Wrappers.<InvDispatchTarget>lambdaQuery()
                        .eq(InvDispatchTarget::getOwnerId, ownerId).eq(InvDispatchTarget::getDispatchId, dispatchId)
                        .orderByAsc(InvDispatchTarget::getTargetType))
                .forEach(t -> map.put(t.getTargetType(), t));
        return map;
    }

    void replaceTargets(Caller caller, InvDispatchItem row, Collection<InvTargetRef> next, String rootType) {
        targetMapper.delete(Wrappers.<InvDispatchTarget>lambdaQuery()
                .eq(InvDispatchTarget::getOwnerId, caller.ownerId())
                .eq(InvDispatchTarget::getDispatchId, row.getDispatchId()));
        for (InvTargetRef t : next) {
            insertTarget(caller, row.getDispatchId(), t.getObjectType(), t.getObjectRef(), t.getName());
            if (t.getObjectType().equals(rootType)) {
                row.setRootTargetType(t.getObjectType());
                row.setRootTargetRef(t.getObjectRef());
            }
        }
    }

    void recordAdjust(Caller caller, String dispatchId, String action, Object before, Object after, String reason) {
        InvDispatchAdjust row = new InvDispatchAdjust();
        row.setOwnerId(caller.ownerId());
        row.setDispatchId(dispatchId);
        row.setAction(action);
        row.setBeforeBody(before == null ? null : JSON.writeValueAsString(before));
        row.setAfterBody(after == null ? null : JSON.writeValueAsString(after));
        row.setReason(reason);
        row.setCreatedBy(HostDocumentSupport.operator(caller));
        adjustMapper.insert(row);
    }

    /**
     * 退货入库：这件设备最近一条未结的出库池记录标为已退回。
     *
     * @return 退回前的状态（{@code CLAIMED} 表示宿主那边可能还挂着业务，要加「退货待确认」冻结）；
     *         没有未结记录时为空
     */
    String markReturned(Caller caller, String serialId) {
        InvDispatchItem row = itemMapper.selectOne(Wrappers.<InvDispatchItem>lambdaQuery()
                .eq(InvDispatchItem::getOwnerId, caller.ownerId())
                .eq(InvDispatchItem::getSerialId, serialId)
                .in(InvDispatchItem::getStatus, AVAILABLE, CLAIMED)
                .orderByDesc(InvDispatchItem::getId)
                .last("LIMIT 1 FOR UPDATE"));
        if (row == null) {
            return null;
        }
        String before = row.getStatus();
        closeActiveClaim(caller, row.getDispatchId(), "CLOSED");
        setStatus(caller, row, RETURNED, "ReturnReceived");
        return before;
    }

    void closeActiveClaim(Caller caller, String dispatchId, String status) {
        claimMapper.update(null, Wrappers.<InvDispatchClaim>lambdaUpdate()
                .set(InvDispatchClaim::getStatus, status)
                .set(InvDispatchClaim::getReleasedAt, LocalDateTime.now())
                .set(InvDispatchClaim::getReleasedBy, HostDocumentSupport.operator(caller))
                .eq(InvDispatchClaim::getOwnerId, caller.ownerId())
                .eq(InvDispatchClaim::getDispatchId, dispatchId)
                .eq(InvDispatchClaim::getStatus, "ACTIVE"));
    }

    void publish(Caller caller, String eventType, InvDispatchItem row) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("outboundNo", row.getOutboundNo());
        payload.put("itemId", row.getItemId());
        payload.put("serialNo", row.getSerialNo());
        payload.put("qty", row.getQty());
        payload.put("status", row.getStatus());
        payload.put("parentDispatchId", row.getParentDispatchId());
        payload.put("rootTargetType", row.getRootTargetType());
        payload.put("rootTargetRef", row.getRootTargetRef());
        events.publish(caller, eventType, RESOURCE, row.getDispatchId(), row.getResourceVersion(), payload);
    }

    private InvDispatchItem base(Caller caller, InvOutboundOrder order, int lineNo, String itemId, BigDecimal qty,
                                 InvDocumentTarget root) {
        InvDispatchItem row = new InvDispatchItem();
        row.setDispatchId(InvKeys.next(InvKeys.DISPATCH));
        row.setOwnerId(caller.ownerId());
        row.setOutboundNo(order.getOutboundNo());
        row.setLineNo(lineNo);
        row.setItemId(itemId);
        row.setLocationId(order.getLocationId());
        row.setQty(qty);
        row.setRootTargetType(root.getTargetType());
        row.setRootTargetRef(root.getTargetRef());
        row.setStatus(AVAILABLE);
        row.setResourceVersion(1);
        row.setCreatedBy(HostDocumentSupport.operator(caller));
        return row;
    }

    private void insertTarget(Caller caller, String dispatchId, String type, String ref, String name) {
        InvDispatchTarget t = new InvDispatchTarget();
        t.setOwnerId(caller.ownerId());
        t.setDispatchId(dispatchId);
        t.setTargetType(type);
        t.setTargetRef(ref);
        t.setNameSnapshot(name);
        t.setCreatedBy(HostDocumentSupport.operator(caller));
        targetMapper.insert(t);
    }
}
