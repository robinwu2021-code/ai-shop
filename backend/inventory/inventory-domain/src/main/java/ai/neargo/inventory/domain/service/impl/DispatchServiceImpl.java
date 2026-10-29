package ai.neargo.inventory.domain.service.impl;

import ai.neargo.inventory.api.InventoryError;
import ai.neargo.inventory.api.InventoryException;
import ai.neargo.inventory.api.profile.BusinessProfile.OutboundPurpose;
import ai.neargo.inventory.domain.config.ConditionalOnInventory;
import ai.neargo.inventory.domain.entity.InvDispatchClaim;
import ai.neargo.inventory.domain.entity.InvDispatchItem;
import ai.neargo.inventory.domain.entity.InvDispatchTarget;
import ai.neargo.inventory.domain.entity.InvOutboundOrder;
import ai.neargo.inventory.domain.entity.InvStockEpisode;
import ai.neargo.inventory.domain.entity.InvTargetRef;
import ai.neargo.inventory.domain.mapper.InventoryMappers.DispatchClaimMapper;
import ai.neargo.inventory.domain.mapper.InventoryMappers.DispatchItemMapper;
import ai.neargo.inventory.domain.mapper.InventoryMappers.DispatchTargetMapper;
import ai.neargo.inventory.domain.mapper.InventoryMappers.OutboundOrderMapper;
import ai.neargo.inventory.domain.mapper.InventoryMappers.StockEpisodeMapper;
import ai.neargo.inventory.domain.service.AuditService;
import ai.neargo.inventory.domain.service.Caller;
import ai.neargo.inventory.domain.service.DispatchService;
import ai.neargo.inventory.domain.service.HostInboundService;
import ai.neargo.inventory.domain.support.InvKeys;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

@ConditionalOnInventory
@Service
public class DispatchServiceImpl implements DispatchService {

    private final DispatchPoolStore pool;
    private final HostDocumentSupport support;
    private final DispatchItemMapper itemMapper;
    private final DispatchTargetMapper targetMapper;
    private final DispatchClaimMapper claimMapper;
    private final OutboundOrderMapper outboundMapper;
    private final StockEpisodeMapper episodeMapper;
    private final HostInboundService inbounds;
    private final AuditService audit;

    public DispatchServiceImpl(DispatchPoolStore pool, HostDocumentSupport support, DispatchItemMapper itemMapper,
                               DispatchTargetMapper targetMapper, DispatchClaimMapper claimMapper,
                               OutboundOrderMapper outboundMapper, StockEpisodeMapper episodeMapper,
                               HostInboundService inbounds, AuditService audit) {
        this.pool = pool;
        this.support = support;
        this.itemMapper = itemMapper;
        this.targetMapper = targetMapper;
        this.claimMapper = claimMapper;
        this.outboundMapper = outboundMapper;
        this.episodeMapper = episodeMapper;
        this.inbounds = inbounds;
        this.audit = audit;
    }

    @Override
    public List<View> query(String ownerId, Filter f) {
        List<String> byTarget = null;
        if (f.targetType() != null && f.targetRefs() != null && !f.targetRefs().isEmpty()) {
            byTarget = targetMapper.selectList(Wrappers.<InvDispatchTarget>lambdaQuery()
                            .eq(InvDispatchTarget::getOwnerId, ownerId)
                            .eq(InvDispatchTarget::getTargetType, f.targetType())
                            .in(InvDispatchTarget::getTargetRef, f.targetRefs()))
                    .stream().map(InvDispatchTarget::getDispatchId).distinct().toList();
            if (byTarget.isEmpty()) {
                return List.of();
            }
        }
        List<InvDispatchItem> rows = itemMapper.selectList(Wrappers.<InvDispatchItem>lambdaQuery()
                .eq(InvDispatchItem::getOwnerId, ownerId)
                .in(f.statuses() != null && !f.statuses().isEmpty(), InvDispatchItem::getStatus, f.statuses())
                .in(f.rootTargetRefs() != null && !f.rootTargetRefs().isEmpty(), InvDispatchItem::getRootTargetRef,
                        f.rootTargetRefs())
                .in(byTarget != null, InvDispatchItem::getDispatchId, byTarget)
                .eq(f.outboundNo() != null, InvDispatchItem::getOutboundNo, f.outboundNo())
                .eq(f.serialNo() != null, InvDispatchItem::getSerialNo, f.serialNo())
                .eq(f.itemId() != null, InvDispatchItem::getItemId, f.itemId())
                .orderByAsc(InvDispatchItem::getId)
                .last("LIMIT 2000"));
        return rows.stream().map(r -> view(ownerId, r)).toList();
    }

    @Override
    @Transactional(transactionManager = "invTransactionManager")
    public View redirect(Caller caller, String dispatchId, Map<String, String> targets, String reason) {
        requireReason(reason);
        InvDispatchItem head = pool.requireHead(caller.ownerId(), dispatchId);
        List<InvDispatchItem> family = pool.family(caller.ownerId(), head);
        requireAvailable(family);
        OutboundPurpose purpose = purposeOf(caller, head);
        Map<String, InvTargetRef> next = support.resolveTargets(caller, purpose, targets == null ? Map.of() : targets);
        Map<String, String> before = new LinkedHashMap<>();
        pool.targets(caller.ownerId(), dispatchId).forEach((type, t) -> before.put(type, t.getTargetRef()));
        for (InvDispatchItem row : family) {
            pool.replaceTargets(caller, row, next.values(), purpose.requiredTargets().get(0));
            pool.setStatus(caller, row, DispatchPoolStore.AVAILABLE, "DispatchRedirected");
        }
        pool.recordAdjust(caller, dispatchId, "REDIRECT", before, targets, reason);
        audit.record(caller, "dispatch.redirect", "DISPATCH", dispatchId, before, targets, reason);
        return view(caller.ownerId(), pool.lock(caller.ownerId(), dispatchId));
    }

    @Override
    @Transactional(transactionManager = "invTransactionManager")
    public String revoke(Caller caller, String dispatchId, String reason) {
        requireReason(reason);
        InvDispatchItem head = pool.requireHead(caller.ownerId(), dispatchId);
        List<InvDispatchItem> family = pool.family(caller.ownerId(), head);
        requireAvailable(family);
        for (InvDispatchItem row : family) {
            pool.setStatus(caller, row, DispatchPoolStore.REVOKED, "DispatchRevoked");
        }
        // 纠正入库：回到发出的库位，开新在库周期、恢复余额；放行凭证沿用发出时那一轮的；组装关系照原样还原
        Map<String, List<HostInboundService.Serial>> serials = new LinkedHashMap<>();
        Map<String, BigDecimal> quantities = new LinkedHashMap<>();
        for (InvDispatchItem row : family) {
            if (row.getSerialId() != null) {
                InvStockEpisode closed = episodeMapper.selectOne(Wrappers.<InvStockEpisode>lambdaQuery()
                        .eq(InvStockEpisode::getOwnerId, caller.ownerId())
                        .eq(InvStockEpisode::getEpisodeId, row.getEpisodeId()));
                String parentNo = row.getParentDispatchId() == null ? null : head.getSerialNo();
                serials.computeIfAbsent(row.getItemId(), k -> new ArrayList<>()).add(new HostInboundService.Serial(
                        row.getSerialNo(), closed == null ? null : closed.getGateRef(), parentNo));
            } else {
                quantities.merge(row.getItemId(), row.getQty(), BigDecimal::add);
            }
        }
        List<HostInboundService.Line> lines = new ArrayList<>();
        serials.forEach((item, list) -> lines.add(new HostInboundService.Line(item, null, null, list)));
        // 数量物料发出时没有逐行的凭证可沿用：用「撤回 + 记录号」作放行依据，审计里查得到来路
        quantities.forEach((item, qty) -> lines.add(new HostInboundService.Line(item, qty.intValueExact(),
                "REVOKE:" + dispatchId, List.of())));
        String inboundNo = inbounds.correction(caller, head.getLocationId(), dispatchId, lines);
        pool.recordAdjust(caller, dispatchId, "REVOKE", null, inboundNo, reason);
        audit.record(caller, "dispatch.revoke", "DISPATCH", dispatchId, null, inboundNo, reason);
        return inboundNo;
    }

    @Override
    @Transactional(transactionManager = "invTransactionManager")
    public Claim claim(Caller caller, List<String> dispatchIds, String businessRef) {
        if (dispatchIds == null || dispatchIds.isEmpty()) {
            throw InventoryException.of(InventoryError.BAD_REQUEST, "dispatchIds");
        }
        // 固定锁序 + 一次列全：整批全成全败
        Set<String> notAvailable = new LinkedHashSet<>();
        List<InvDispatchItem> all = new ArrayList<>();
        for (String id : new TreeSet<>(dispatchIds)) {
            InvDispatchItem head = pool.lock(caller.ownerId(), id);
            if (head == null || head.getParentDispatchId() != null) {
                notAvailable.add(id);
                continue;
            }
            for (InvDispatchItem row : pool.family(caller.ownerId(), head)) {
                if (!DispatchPoolStore.AVAILABLE.equals(row.getStatus())) {
                    notAvailable.add(id);
                }
                all.add(row);
            }
        }
        if (!notAvailable.isEmpty()) {
            throw InventoryException.of(InventoryError.DISPATCH_NOT_AVAILABLE, notAvailable.toArray());
        }
        String claimNo = InvKeys.next(InvKeys.CLAIM);
        for (InvDispatchItem row : all) {
            InvDispatchClaim c = new InvDispatchClaim();
            c.setClaimNo(claimNo);
            c.setOwnerId(caller.ownerId());
            c.setDispatchId(row.getDispatchId());
            c.setSystemCode(caller.systemCode());
            c.setRequestId(caller.requestId());
            c.setBusinessRef(businessRef);
            c.setStatus("ACTIVE");
            c.setCreatedBy(HostDocumentSupport.operator(caller));
            try {
                claimMapper.insert(c);
            } catch (DuplicateKeyException race) {
                throw InventoryException.of(InventoryError.DISPATCH_NOT_AVAILABLE, row.getDispatchId());
            }
            pool.setStatus(caller, row, DispatchPoolStore.CLAIMED, "DispatchClaimed");
        }
        audit.record(caller, "dispatch.claim", "CLAIM", claimNo, null, dispatchIds, businessRef);
        return new Claim(claimNo, all.stream().map(InvDispatchItem::getDispatchId).toList());
    }

    @Override
    public Claim claimByRequest(Caller caller, String requestId) {
        List<InvDispatchClaim> rows = claimMapper.selectList(Wrappers.<InvDispatchClaim>lambdaQuery()
                .eq(InvDispatchClaim::getOwnerId, caller.ownerId())
                .eq(InvDispatchClaim::getRequestId, requestId)
                .orderByAsc(InvDispatchClaim::getId));
        if (rows.isEmpty()) {
            throw InventoryException.of(InventoryError.NOT_FOUND, "claim");
        }
        return new Claim(rows.get(0).getClaimNo(), rows.stream().map(InvDispatchClaim::getDispatchId).toList());
    }

    @Override
    @Transactional(transactionManager = "invTransactionManager")
    public void release(Caller caller, String claimNo) {
        List<InvDispatchClaim> rows = claimMapper.selectList(Wrappers.<InvDispatchClaim>lambdaQuery()
                .eq(InvDispatchClaim::getOwnerId, caller.ownerId())
                .eq(InvDispatchClaim::getClaimNo, claimNo)
                .eq(InvDispatchClaim::getStatus, "ACTIVE")
                .last("FOR UPDATE"));
        if (rows.isEmpty()) {
            throw InventoryException.of(InventoryError.NOT_FOUND, "claim");
        }
        if (rows.stream().anyMatch(c -> !c.getSystemCode().equals(caller.systemCode()))) {
            // 只有原领取系统能释放：它最清楚这批货在业务上有没有被占用
            throw InventoryException.of(InventoryError.FORBIDDEN, "claim.system");
        }
        for (InvDispatchClaim c : rows) {
            pool.closeActiveClaim(caller, c.getDispatchId(), "RELEASED");
            InvDispatchItem row = pool.lock(caller.ownerId(), c.getDispatchId());
            if (DispatchPoolStore.CLAIMED.equals(row.getStatus())) {
                pool.setStatus(caller, row, DispatchPoolStore.AVAILABLE, "DispatchReleased");
            }
        }
        audit.record(caller, "dispatch.release", "CLAIM", claimNo, null, null, null);
    }

    @Override
    public List<InTransit> inTransit(String ownerId) {
        Map<String, InTransit> sums = new LinkedHashMap<>();
        for (InvDispatchItem row : itemMapper.selectList(Wrappers.<InvDispatchItem>lambdaQuery()
                .eq(InvDispatchItem::getOwnerId, ownerId)
                .eq(InvDispatchItem::getStatus, DispatchPoolStore.AVAILABLE)
                .orderByAsc(InvDispatchItem::getRootTargetRef, InvDispatchItem::getItemId))) {
            String key = row.getRootTargetType() + "\n" + row.getRootTargetRef() + "\n" + row.getItemId();
            sums.merge(key, new InTransit(row.getRootTargetType(), row.getRootTargetRef(), row.getItemId(),
                    row.getQty()), (a, b) -> new InTransit(a.rootTargetType(), a.rootTargetRef(), a.itemId(),
                    a.qty().add(b.qty())));
        }
        return List.copyOf(sums.values());
    }

    private OutboundPurpose purposeOf(Caller caller, InvDispatchItem row) {
        InvOutboundOrder order = outboundMapper.selectOne(Wrappers.<InvOutboundOrder>lambdaQuery()
                .eq(InvOutboundOrder::getOwnerId, caller.ownerId())
                .eq(InvOutboundOrder::getOutboundNo, row.getOutboundNo()));
        return HostDocumentSupport.outboundPurpose(support.profile(caller.ownerId()).profile(), order.getPurpose());
    }

    /** 只有待签收能调整 / 撤回：已签收的宿主那边可能已经装上了，直接改会两边对不上。 */
    private static void requireAvailable(List<InvDispatchItem> family) {
        for (InvDispatchItem row : family) {
            if (!DispatchPoolStore.AVAILABLE.equals(row.getStatus())) {
                throw InventoryException.of(InventoryError.DISPATCH_NOT_AVAILABLE, row.getDispatchId());
            }
        }
    }

    private static void requireReason(String reason) {
        if (reason == null || reason.isBlank() || reason.length() > 255) {
            throw InventoryException.of(InventoryError.BAD_REQUEST, "reason");
        }
    }

    private View view(String ownerId, InvDispatchItem r) {
        Map<String, TargetView> targets = new LinkedHashMap<>();
        pool.targets(ownerId, r.getDispatchId())
                .forEach((type, t) -> targets.put(type, new TargetView(t.getTargetRef(), t.getNameSnapshot())));
        return new View(r.getDispatchId(), r.getOutboundNo(), r.getItemId(), r.getSerialNo(), r.getQty(),
                r.getLocationId(), r.getStatus(), r.getParentDispatchId(), r.getRootTargetType(),
                r.getRootTargetRef(), targets, r.getResourceVersion());
    }
}
