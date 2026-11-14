package ai.neargo.inventory.domain.service.impl;

import ai.neargo.inventory.api.InventoryError;
import ai.neargo.inventory.api.InventoryException;
import ai.neargo.inventory.api.profile.BusinessProfile.CountReview;
import ai.neargo.inventory.domain.config.ConditionalOnInventory;
import ai.neargo.inventory.domain.entity.InvCountScan;
import ai.neargo.inventory.domain.entity.InvItem;
import ai.neargo.inventory.domain.entity.InvSerial;
import ai.neargo.inventory.domain.entity.InvStockBalance;
import ai.neargo.inventory.domain.entity.InvStockCount;
import ai.neargo.inventory.domain.entity.InvStockCountLine;
import ai.neargo.inventory.domain.entity.InvStockEpisode;
import ai.neargo.inventory.domain.mapper.InventoryMappers.BalanceMapper;
import ai.neargo.inventory.domain.mapper.InventoryMappers.CountScanMapper;
import ai.neargo.inventory.domain.mapper.InventoryMappers.SerialMapper;
import ai.neargo.inventory.domain.mapper.InventoryMappers.StockCountLineMapper;
import ai.neargo.inventory.domain.mapper.InventoryMappers.StockCountMapper;
import ai.neargo.inventory.domain.mapper.InventoryMappers.StockEpisodeMapper;
import ai.neargo.inventory.domain.service.AuditService;
import ai.neargo.inventory.domain.service.BusinessProfileService.Versioned;
import ai.neargo.inventory.domain.service.Caller;
import ai.neargo.inventory.domain.service.EventPublisher;
import ai.neargo.inventory.domain.service.HostCountService;
import ai.neargo.inventory.domain.service.HostInboundService;
import ai.neargo.inventory.domain.service.HostOutboundService;
import ai.neargo.inventory.domain.support.InvEnums;
import ai.neargo.inventory.domain.support.InvKeys;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

@ConditionalOnInventory
@Service
public class HostCountServiceImpl implements HostCountService {

    static final String SUBMITTED = "SUBMITTED";
    static final String APPROVED = "APPROVED";
    static final String IN_SCOPE = "IN_SCOPE";

    private final HostDocumentSupport support;
    private final InventoryMutationGuard locations;
    private final StockCountMapper countMapper;
    private final StockCountLineMapper lineMapper;
    private final CountScanMapper scanMapper;
    private final BalanceMapper balanceMapper;
    private final SerialMapper serialMapper;
    private final StockEpisodeMapper episodeMapper;
    private final HostInboundService inbounds;
    private final HostOutboundService outbounds;
    private final EventPublisher events;
    private final AuditService audit;

    public HostCountServiceImpl(HostDocumentSupport support, InventoryMutationGuard locations,
                                StockCountMapper countMapper, StockCountLineMapper lineMapper,
                                CountScanMapper scanMapper, BalanceMapper balanceMapper, SerialMapper serialMapper,
                                StockEpisodeMapper episodeMapper, HostInboundService inbounds,
                                HostOutboundService outbounds, EventPublisher events, AuditService audit) {
        this.support = support;
        this.locations = locations;
        this.countMapper = countMapper;
        this.lineMapper = lineMapper;
        this.scanMapper = scanMapper;
        this.balanceMapper = balanceMapper;
        this.serialMapper = serialMapper;
        this.episodeMapper = episodeMapper;
        this.inbounds = inbounds;
        this.outbounds = outbounds;
        this.events = events;
        this.audit = audit;
    }

    @Override
    @Transactional(transactionManager = "invTransactionManager")
    public String open(Caller caller, String locationId, List<String> itemIds) {
        support.requireActiveLocation(caller.ownerId(), locationId);
        // 与过账同一把锁：开盘与过账不会同时发生，账面快照取到的是一个静止的数
        locations.lockLocation(caller.ownerId(), locationId);
        if (countMapper.exists(Wrappers.<InvStockCount>lambdaQuery()
                .eq(InvStockCount::getOwnerId, caller.ownerId()).eq(InvStockCount::getLocationId, locationId)
                .in(InvStockCount::getStatus, CountFreezeGuard.OPEN))) {
            throw InventoryException.of(InventoryError.COUNT_ALREADY_OPEN, locationId);
        }
        List<InvStockBalance> balances = balanceMapper.selectList(Wrappers.<InvStockBalance>lambdaQuery()
                .eq(InvStockBalance::getOwnerId, caller.ownerId()).eq(InvStockBalance::getLocationId, locationId));
        List<String> scope = itemIds == null || itemIds.isEmpty()
                ? balances.stream().map(InvStockBalance::getItemId).distinct().toList()
                : itemIds.stream().distinct().toList();
        if (scope.isEmpty()) {
            throw InventoryException.of(InventoryError.BAD_REQUEST, "itemIds");
        }
        Map<String, InvItem> items = support.activeItems(caller.ownerId(), scope);
        // 范围内有活跃预留：冻结会卡住已付款订单的发货
        List<String> reserved = balances.stream()
                .filter(b -> scope.contains(b.getItemId()) && b.getReservedQuantity() != null
                        && b.getReservedQuantity().signum() > 0)
                .map(InvStockBalance::getItemId).toList();
        if (!reserved.isEmpty()) {
            throw InventoryException.of(InventoryError.CONFLICT, "reserved", String.join(",", reserved));
        }
        Versioned profile = support.profile(caller.ownerId());
        InvStockCount count = new InvStockCount();
        count.setCountNo(InvKeys.next(InvKeys.COUNT));
        count.setOwnerId(caller.ownerId());
        count.setLocationId(locationId);
        count.setScope("SELECTED");
        count.setStatus(InvEnums.DocStatus.COUNTING);
        count.setStartedAt(LocalDateTime.now());
        count.setContentVersion(0);
        count.setProfileVersion(profile.version());
        count.setOperator(HostDocumentSupport.operator(caller));
        count.setCreatedBy(HostDocumentSupport.operator(caller));
        countMapper.insert(count);
        int lineNo = 1;
        for (String itemId : scope) {
            InvStockBalance b = balances.stream().filter(x -> x.getItemId().equals(itemId)).findFirst().orElse(null);
            InvStockCountLine line = new InvStockCountLine();
            line.setCountNo(count.getCountNo());
            line.setLineNo(lineNo++);
            line.setOwnerId(caller.ownerId());
            line.setItemId(items.get(itemId).getItemId());
            line.setBookQty(b == null || b.getOnHandQuantity() == null ? 0 : b.getOnHandQuantity().intValueExact());
            line.setCreatedBy(HostDocumentSupport.operator(caller));
            lineMapper.insert(line);
        }
        events.publish(caller, "CountOpened", "COUNT", count.getCountNo(), 1,
                Map.of("locationId", locationId, "itemIds", scope));
        audit.record(caller, "count.open", "COUNT", count.getCountNo(), null, scope, null);
        return count.getCountNo();
    }

    @Override
    @Transactional(transactionManager = "invTransactionManager")
    public List<Scan> scan(Caller caller, String countNo, List<String> serialNos) {
        InvStockCount count = locked(caller, countNo, InvEnums.DocStatus.COUNTING);
        Set<String> scope = scopeItems(caller.ownerId(), countNo);
        List<Scan> results = new ArrayList<>();
        boolean changed = false;
        for (String no : serialNos == null ? List.<String>of() : serialNos) {
            InvCountScan existing = scanMapper.selectOne(Wrappers.<InvCountScan>lambdaQuery()
                    .eq(InvCountScan::getOwnerId, caller.ownerId()).eq(InvCountScan::getCountNo, countNo)
                    .eq(InvCountScan::getSerialNo, no));
            if (existing != null) {
                // 重扫不重计：多人各扫一遍同一个柜，不会盘出两个
                results.add(new Scan(no, existing.getVerdict()));
                continue;
            }
            InvSerial serial = serialMapper.selectOne(Wrappers.<InvSerial>lambdaQuery()
                    .eq(InvSerial::getOwnerId, caller.ownerId()).eq(InvSerial::getSerialNo, no));
            InvCountScan row = new InvCountScan();
            row.setOwnerId(caller.ownerId());
            row.setCountNo(countNo);
            row.setSerialNo(no);
            row.setVerdict(verdict(caller.ownerId(), count.getLocationId(), scope, serial));
            if (serial != null) {
                row.setSerialId(serial.getSerialId());
                row.setItemId(serial.getItemId());
            }
            row.setCreatedBy(HostDocumentSupport.operator(caller));
            try {
                scanMapper.insert(row);
                changed = true;
            } catch (DuplicateKeyException race) {
                // 两人同时扫同一件：另一个人先记上了，结果一样
            }
            results.add(new Scan(no, row.getVerdict()));
        }
        if (changed) {
            bump(count);
        }
        return results;
    }

    @Override
    @Transactional(transactionManager = "invTransactionManager")
    public void count(Caller caller, String countNo, String itemId, int countedQty) {
        InvStockCount count = locked(caller, countNo, InvEnums.DocStatus.COUNTING);
        if (countedQty < 0) {
            throw InventoryException.of(InventoryError.BAD_REQUEST, "countedQty");
        }
        InvItem item = support.activeItems(caller.ownerId(), List.of(itemId)).get(itemId);
        if (HostDocumentSupport.serial(item)) {
            // 逐件物料的实盘数只能由扫码得出：手填一个数，就不知道少的是哪几台
            throw InventoryException.of(InventoryError.BAD_REQUEST, "serial:" + itemId);
        }
        int changed = lineMapper.update(null, Wrappers.<InvStockCountLine>lambdaUpdate()
                .set(InvStockCountLine::getCountedQty, countedQty)
                .set(InvStockCountLine::getUpdatedBy, HostDocumentSupport.operator(caller))
                .eq(InvStockCountLine::getOwnerId, caller.ownerId())
                .eq(InvStockCountLine::getCountNo, countNo)
                .eq(InvStockCountLine::getItemId, itemId));
        if (changed == 0) {
            throw InventoryException.of(InventoryError.BAD_REQUEST, "scope:" + itemId);
        }
        bump(count);
    }

    @Override
    @Transactional(transactionManager = "invTransactionManager")
    public void submit(Caller caller, String countNo) {
        InvStockCount count = locked(caller, countNo, InvEnums.DocStatus.COUNTING);
        count.setStatus(SUBMITTED);
        count.setSubmittedBy(HostDocumentSupport.operator(caller));
        count.setUpdatedBy(count.getSubmittedBy());
        countMapper.updateById(count);
        audit.record(caller, "count.submit", "COUNT", countNo, null, count.getContentVersion(), null);
    }

    @Override
    @Transactional(transactionManager = "invTransactionManager")
    public void reject(Caller caller, String countNo, String note) {
        InvStockCount count = locked(caller, countNo, SUBMITTED, APPROVED);
        // 驳回回到录数；批准作废 —— 再改之后要重新复核
        countMapper.update(null, Wrappers.<InvStockCount>lambdaUpdate()
                .set(InvStockCount::getStatus, InvEnums.DocStatus.COUNTING)
                .set(InvStockCount::getApprovedVersion, null)
                .set(InvStockCount::getReviewer, HostDocumentSupport.operator(caller))
                .set(InvStockCount::getReviewNote, note)
                .eq(InvStockCount::getId, count.getId()));
        audit.record(caller, "count.reject", "COUNT", countNo, null, null, note);
    }

    @Override
    @Transactional(transactionManager = "invTransactionManager")
    public void approve(Caller caller, String countNo, String note) {
        InvStockCount count = locked(caller, countNo, SUBMITTED);
        String reviewer = HostDocumentSupport.operator(caller);
        CountReview review = support.profile(caller.ownerId()).profile().countReview();
        if (review.reviewerMustDiffer() && Objects.equals(reviewer, count.getSubmittedBy())) {
            throw InventoryException.of(InventoryError.FORBIDDEN, "reviewer");
        }
        count.setStatus(APPROVED);
        count.setReviewer(reviewer);
        count.setReviewNote(note);
        count.setApprovedVersion(count.getContentVersion());
        count.setUpdatedBy(reviewer);
        countMapper.updateById(count);
        audit.record(caller, "count.approve", "COUNT", countNo, null, count.getApprovedVersion(), note);
    }

    @Override
    @Transactional(transactionManager = "invTransactionManager")
    public Posted post(Caller caller, String countNo) {
        CountReview review = support.profile(caller.ownerId()).profile().countReview();
        InvStockCount count = review.required() ? locked(caller, countNo, APPROVED)
                : locked(caller, countNo, SUBMITTED, APPROVED);
        if (review.required() && !Objects.equals(count.getApprovedVersion(), count.getContentVersion())) {
            throw InventoryException.of(InventoryError.CONFLICT, "approvedVersion");
        }
        List<InvStockCountLine> lines = lines(caller.ownerId(), countNo);
        Map<String, InvItem> items = support.activeItems(caller.ownerId(),
                lines.stream().map(InvStockCountLine::getItemId).toList());
        List<HostInboundService.Line> gains = new ArrayList<>();
        List<HostOutboundService.Line> losses = new ArrayList<>();
        List<String> missingSerials = new ArrayList<>();
        List<String> uncounted = new ArrayList<>();
        for (InvStockCountLine line : lines) {
            InvItem item = items.get(line.getItemId());
            int counted;
            if (HostDocumentSupport.serial(item)) {
                List<String> missing = missing(caller.ownerId(), countNo, count.getLocationId(), line.getItemId());
                missingSerials.addAll(missing);
                counted = line.getBookQty() - missing.size();
            } else if (line.getCountedQty() == null) {
                uncounted.add(line.getItemId());
                continue;
            } else {
                counted = line.getCountedQty();
                int diff = counted - line.getBookQty();
                if (diff > 0) {
                    gains.add(new HostInboundService.Line(line.getItemId(), diff, "COUNT:" + countNo, List.of()));
                } else if (diff < 0) {
                    losses.add(new HostOutboundService.Line(line.getItemId(), -diff));
                }
            }
            line.setCountedQty(counted);
            line.setDiffQty(counted - line.getBookQty());
            lineMapper.updateById(line);
        }
        if (!uncounted.isEmpty()) {
            throw InventoryException.of(InventoryError.BAD_REQUEST, uncounted.stream().map(i -> "uncounted:" + i)
                    .toArray());
        }
        String gainNo = gains.isEmpty() ? null
                : inbounds.countGain(caller, count.getLocationId(), countNo, gains);
        String lossNo = losses.isEmpty() && missingSerials.isEmpty() ? null
                : outbounds.countLoss(caller, count.getLocationId(), countNo, losses, missingSerials);
        count.setStatus(InvEnums.DocStatus.POSTED);
        count.setPostedAt(LocalDateTime.now());
        count.setGainInboundNo(gainNo);
        count.setLossOutboundNo(lossNo);
        count.setUpdatedBy(HostDocumentSupport.operator(caller));
        countMapper.updateById(count);
        events.publish(caller, "CountPosted", "COUNT", countNo, 2,
                Map.of("gainInboundNo", String.valueOf(gainNo), "lossOutboundNo", String.valueOf(lossNo)));
        audit.record(caller, "count.post", "COUNT", countNo, null, null, null);
        return new Posted(gainNo, lossNo);
    }

    @Override
    @Transactional(transactionManager = "invTransactionManager")
    public void cancel(Caller caller, String countNo) {
        InvStockCount count = locked(caller, countNo, InvEnums.DocStatus.COUNTING, SUBMITTED, APPROVED);
        count.setStatus(InvEnums.DocStatus.VOIDED);
        count.setUpdatedBy(HostDocumentSupport.operator(caller));
        countMapper.updateById(count);
        events.publish(caller, "CountCancelled", "COUNT", countNo, 2, null);
        audit.record(caller, "count.cancel", "COUNT", countNo, null, null, null);
    }

    @Override
    public View view(String ownerId, String countNo) {
        InvStockCount count = countMapper.selectOne(Wrappers.<InvStockCount>lambdaQuery()
                .eq(InvStockCount::getOwnerId, ownerId).eq(InvStockCount::getCountNo, countNo));
        if (count == null) {
            throw InventoryException.of(InventoryError.NOT_FOUND, "count");
        }
        List<InvStockCountLine> lines = lines(ownerId, countNo);
        Map<String, InvItem> items = support.activeItems(ownerId,
                lines.stream().map(InvStockCountLine::getItemId).toList());
        boolean open = CountFreezeGuard.OPEN.contains(count.getStatus());
        List<String> missing = new ArrayList<>();
        List<Line> views = new ArrayList<>();
        for (InvStockCountLine l : lines) {
            InvItem item = items.get(l.getItemId());
            Integer counted = l.getCountedQty();
            if (open && HostDocumentSupport.serial(item)) {
                List<String> m = missing(ownerId, countNo, count.getLocationId(), l.getItemId());
                missing.addAll(m);
                counted = l.getBookQty() - m.size();
            }
            views.add(new Line(l.getItemId(), item.getTrackingMode(), l.getBookQty(), counted,
                    counted == null ? null : counted - l.getBookQty()));
        }
        List<Scan> exceptions = scanMapper.selectList(Wrappers.<InvCountScan>lambdaQuery()
                        .eq(InvCountScan::getOwnerId, ownerId).eq(InvCountScan::getCountNo, countNo)
                        .ne(InvCountScan::getVerdict, IN_SCOPE).orderByAsc(InvCountScan::getSerialNo))
                .stream().map(s -> new Scan(s.getSerialNo(), s.getVerdict())).toList();
        return new View(countNo, count.getLocationId(), count.getStatus(),
                count.getContentVersion() == null ? 0 : count.getContentVersion(), count.getApprovedVersion(),
                count.getSubmittedBy(), count.getReviewer(), views, exceptions, missing);
    }

    /** 账面在仓（范围内物料、本库位、在库周期开着）但没被扫到的 SN。 */
    private List<String> missing(String ownerId, String countNo, String locationId, String itemId) {
        Set<String> scanned = new HashSet<>();
        scanMapper.selectList(Wrappers.<InvCountScan>lambdaQuery()
                        .eq(InvCountScan::getOwnerId, ownerId).eq(InvCountScan::getCountNo, countNo)
                        .eq(InvCountScan::getVerdict, IN_SCOPE).eq(InvCountScan::getItemId, itemId))
                .forEach(s -> scanned.add(s.getSerialId()));
        List<String> serialIds = episodeMapper.selectList(Wrappers.<InvStockEpisode>lambdaQuery()
                        .eq(InvStockEpisode::getOwnerId, ownerId).eq(InvStockEpisode::getLocationId, locationId)
                        .eq(InvStockEpisode::getItemId, itemId).eq(InvStockEpisode::getStatus, "OPEN"))
                .stream().map(InvStockEpisode::getSerialId).filter(id -> !scanned.contains(id)).toList();
        if (serialIds.isEmpty()) {
            return List.of();
        }
        return serialMapper.selectList(Wrappers.<InvSerial>lambdaQuery()
                        .eq(InvSerial::getOwnerId, ownerId).in(InvSerial::getSerialId, serialIds)
                        .orderByAsc(InvSerial::getSerialNo))
                .stream().map(InvSerial::getSerialNo).toList();
    }

    private String verdict(String ownerId, String locationId, Set<String> scope, InvSerial serial) {
        if (serial == null) {
            return "UNKNOWN";
        }
        InvStockEpisode episode = serial.getActiveEpisodeId() == null ? null
                : episodeMapper.selectOne(Wrappers.<InvStockEpisode>lambdaQuery()
                        .eq(InvStockEpisode::getOwnerId, ownerId)
                        .eq(InvStockEpisode::getEpisodeId, serial.getActiveEpisodeId()));
        if (episode == null || !locationId.equals(episode.getLocationId())) {
            // 档案有、却不在这个仓：多半是没登记的退货，提示人去查，不自动当盘盈
            return "NOT_IN_STOCK";
        }
        return scope.contains(serial.getItemId()) ? IN_SCOPE : "OUT_OF_SCOPE";
    }

    private InvStockCount locked(Caller caller, String countNo, String... statuses) {
        InvStockCount count = countMapper.selectOne(Wrappers.<InvStockCount>lambdaQuery()
                .eq(InvStockCount::getOwnerId, caller.ownerId()).eq(InvStockCount::getCountNo, countNo)
                .last("FOR UPDATE"));
        if (count == null) {
            throw InventoryException.of(InventoryError.NOT_FOUND, "count");
        }
        if (!new LinkedHashSet<>(List.of(statuses)).contains(count.getStatus())) {
            throw InventoryException.of(InventoryError.CONFLICT, "status:" + count.getStatus());
        }
        return count;
    }

    /** 内容一变，版本 +1：已批准的会因版本对不上而失效。 */
    private void bump(InvStockCount count) {
        count.setContentVersion((count.getContentVersion() == null ? 0 : count.getContentVersion()) + 1);
        countMapper.updateById(count);
    }

    private Set<String> scopeItems(String ownerId, String countNo) {
        Set<String> scope = new HashSet<>();
        lines(ownerId, countNo).forEach(l -> scope.add(l.getItemId()));
        return scope;
    }

    private List<InvStockCountLine> lines(String ownerId, String countNo) {
        return lineMapper.selectList(Wrappers.<InvStockCountLine>lambdaQuery()
                .eq(InvStockCountLine::getOwnerId, ownerId).eq(InvStockCountLine::getCountNo, countNo)
                .orderByAsc(InvStockCountLine::getLineNo));
    }
}
