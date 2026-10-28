package ai.neargo.shop.inventory.service.impl;

import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.inventory.config.ConditionalOnInventory;
import ai.neargo.shop.inventory.entity.InvReservation;
import ai.neargo.shop.inventory.entity.InvReservationLine;
import ai.neargo.shop.inventory.entity.InvStockBalance;
import ai.neargo.shop.inventory.mapper.InventoryMappers.BalanceMapper;
import ai.neargo.shop.inventory.mapper.InventoryMappers.ReservationLineMapper;
import ai.neargo.shop.inventory.mapper.InventoryMappers.ReservationMapper;
import ai.neargo.shop.inventory.service.InboundService;
import ai.neargo.shop.inventory.service.OutboundService;
import ai.neargo.shop.inventory.service.ReservationService;
import ai.neargo.shop.inventory.support.InvEnums;
import ai.neargo.shop.inventory.support.InvKeys;
import ai.neargo.shop.spi.product.StockDispositionContract;
import ai.neargo.shop.spi.product.StockPort;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 预留协议实现。 */
@ConditionalOnInventory
@Service
public class ReservationServiceImpl implements ReservationService {

    /** 一次回收多少条。**有上界**：一次全捞会在预留积压时把这一轮任务拖成长事务。 */
    private static final int EXPIRE_BATCH_MAX = 200;

    /**
     * 预留时长的平台上限（秒），默认 24 小时。
     *
     * <p><b>今天没有任何调用方能触发它</b>：三个 reserve 的调用点传的都是常量，
     * 开放 API（{@code /open/v1/*}）也只有 items / ledger / sync，没有 reserve。
     * 所以这不是在堵一个正在被利用的洞 —— 需求文档里「今天传个长值就能把货占住」
     * 那句话说过头了。
     *
     * <p>它堵的是<b>下一个调用方</b>：这个方法收任意 {@code ttlSeconds}，
     * 而预留期间那批货<b>谁也买不走</b>。哪天有人把它接到外部输入上
     * （开放 API v2、B 端的某个接口），一个很大的数就能把一家店的货占住，
     * 而且<b>不报错、不告警，只表现为「那件货一直显示卖光了」</b>。
     * 上限放在这一层，因为这里是所有调用方唯一必经的地方。
     */
    @Value("${shop.inventory.reserve.max-ttl-seconds:86400}")
    private long maxTtlSeconds;

    private final ReservationMapper resMapper;
    private final ReservationLineMapper lineMapper;
    private final BalanceMapper balanceMapper;
    private final OutboundService outbound;
    private final InboundService inbound;

    public ReservationServiceImpl(ReservationMapper resMapper, ReservationLineMapper lineMapper,
                                  BalanceMapper balanceMapper, OutboundService outbound,
                                  InboundService inbound) {
        this.resMapper = resMapper;
        this.lineMapper = lineMapper;
        this.balanceMapper = balanceMapper;
        this.outbound = outbound;
        this.inbound = inbound;
    }

    @Override
    @Transactional(transactionManager = "invTransactionManager")
    public String reserve(String ownerId, String externalRef, List<Line> lines, long ttlSeconds) {
        return reserve(ownerId, externalRef, lines, ttlSeconds, 1);
    }

    @Override
    @Transactional(transactionManager = "invTransactionManager")
    public String reserve(String ownerId, String externalRef, List<Line> lines, long ttlSeconds, int lifecycleVersion) {
        if (lifecycleVersion != 1 && lifecycleVersion != 2) throw BizException.of(ErrorCode.BAD_REQUEST);
        String sourceManifest = sourceManifest(lines, lifecycleVersion);
        InvReservation exists = find(ownerId, externalRef);
        if (exists != null) {
            if (InvEnums.ReservationStatus.RELEASED.equals(exists.getStatus())
                    || InvEnums.ReservationStatus.EXPIRED.equals(exists.getStatus())) {
                throw BizException.of(ErrorCode.CONFLICT);
            }
            if (sourceManifest != null || exists.getSourceManifestHash() != null) {
                if (!java.util.Objects.equals(sourceManifest, exists.getSourceManifestHash())
                        || !java.util.Objects.equals(lifecycleVersion, exists.getLifecycleVersion())
                        || !sameSourceLines(lines, linesOf(exists.getReservationId()))) {
                    throw BizException.of(ErrorCode.CONFLICT);
                }
            }
            // Return the exact complete frozen original, never reserve twice or adopt today's ACL.
            return exists.getReservationId();
        }
        if (lines == null || lines.isEmpty()) {
            throw BizException.of(ErrorCode.BAD_REQUEST);
        }
        /*
         * 超过上限**直接拒**，不是悄悄改成上限值。
         *
         * 截断的话调用方以为占住了 7 天、实际只占了 1 天，而到期释放是静默的 ——
         * 表现是「那张单的货莫名其妙被别人买走了」，查起来没有任何线索。
         * 拒绝是响的：接的人在联调当天就知道，而不是上线后某一天。
         *
         * <b>只管上限，不管下限。</b>第一版顺手把 {@code ttlSeconds <= 0} 也拒了，
         * 结果撞坏了 {@code InventoryFlowTest#overdueBacklogIsCountable} ——
         * 它<b>故意传负数</b>来造一笔「一落库就已过期」的预留，好验回收任务，
         * 而那是有道理的：真实场景里「过期」是时间走过去造成的，测试里没法等。
         * 需求要的是上限（防止一个大数把货占住），下限是我自己加的，
         * 它没有对应的风险，却挡住了一个正当用法。
         */
        if (ttlSeconds > maxTtlSeconds) {
            throw BizException.of(ErrorCode.BAD_REQUEST);
        }

        List<String> shortages = new ArrayList<>();
        for (Line l : lines) {
            // hold 的守卫是 available - qty >= 0：**不动 on_hand**（不变式 I5）——
            // 没付钱的单不该把实存扣掉，但也不该让别人买到同一件货
            if (balanceMapper.hold(ownerId, l.itemId(), l.locationId(), l.qty()) == 0) {
                shortages.add(l.itemId());
            }
        }
        if (!shortages.isEmpty()) {
            // 全成功或全失败：抛出去让事务回滚，已 hold 的那几件一并撤回
            throw BizException.of(ErrorCode.STOCK_SHORT_ITEMS, String.join(",", shortages));
        }

        String id = InvKeys.next(InvKeys.RESERVATION);
        InvReservation head = new InvReservation();
        head.setReservationId(id);
        head.setOwnerId(ownerId);
        head.setExternalRef(externalRef);
        head.setStatus(InvEnums.ReservationStatus.HELD);
        head.setLifecycleVersion(lifecycleVersion);
        head.setSourceManifestHash(sourceManifest);
        if (sourceManifest != null) {
            head.setDispositionRevision(1L);
        }
        head.setExpiresAt(LocalDateTime.now().plusSeconds(ttlSeconds));
        resMapper.insert(head);

        int lineNo = 1;
        for (Line l : lines) {
            InvReservationLine row = new InvReservationLine();
            row.setReservationId(id);
            row.setLineNo(lineNo++);
            row.setOwnerId(ownerId);
            row.setItemId(l.itemId());
            row.setLocationId(l.locationId());
            row.setQty(l.qty());
            row.setFulfillmentRef(l.fulfillmentRef());
            row.setSourceSkuNo(l.sourceSkuNo());
            row.setSourceStoreNo(l.sourceStoreNo());
            row.setIssuedQty(0);
            lineMapper.insert(row);
        }
        return id;
    }

    @Override
    @Transactional(transactionManager = "invTransactionManager")
    public String commit(String ownerId, String externalRef, String operator) {
        InvReservation head = require(ownerId, externalRef);
        if (InvEnums.ReservationStatus.COMMITTED.equals(head.getStatus())) {
            return head.getOutboundNo();   // 幂等：重复 commit 返回原出库单
        }
        if (!InvEnums.ReservationStatus.HELD.equals(head.getStatus())) {
            // 已释放/已过期的不能再确认 —— 那意味着货已经被别人买走了
            throw BizException.of(ErrorCode.CONFLICT);
        }
        if (Integer.valueOf(2).equals(head.getLifecycleVersion())) throw BizException.of(ErrorCode.CONFLICT);
        List<InvReservationLine> rows = linesOf(head.getReservationId());

        // 先退预留、再出库：两步都是同一行上的条件更新，事务内由行锁串起来。
        // 反过来做的话，中间那一瞬 available 会凭空多出这批量
        for (InvReservationLine r : rows) {
            trackedChanged(
                    head,
                    balanceMapper.unhold(ownerId, r.getItemId(), r.getLocationId(), r.getQty()));
        }
        String locationId = rows.get(0).getLocationId();
        List<OutboundService.Line> outLines = new ArrayList<>();
        for (InvReservationLine r : rows) {
            outLines.add(new OutboundService.Line(r.getItemId(), r.getQty(), null));
        }
        String outboundNo = outbound.postDirectly(new OutboundService.Draft(
                ownerId, locationId, InvEnums.OutboundPurpose.SALE, externalRef,
                head.getReservationId(), null, LocalDateTime.now(), null, outLines), operator);

        head.setStatus(InvEnums.ReservationStatus.COMMITTED);
        head.setCommittedAt(LocalDateTime.now());
        head.setOutboundNo(outboundNo);
        saveHead(head);
        return outboundNo;
    }

    @Override
    @Transactional(transactionManager = "invTransactionManager")
    public void allocatePaidByRef(String externalRef) {
        for (InvReservation head : allByRef(externalRef)) {
            if (Integer.valueOf(2).equals(head.getLifecycleVersion())
                    && InvEnums.ReservationStatus.HELD.equals(head.getStatus())) {
                if (head.getSourceManifestHash() != null) {
                    requireProtected(head, linesOf(head.getReservationId()));
                }
                head.setStatus(InvEnums.ReservationStatus.ALLOCATED);
                head.setCommittedAt(LocalDateTime.now());
                // ALLOCATED is excluded from expiry; this timestamp remains only for compatibility/readers.
                head.setExpiresAt(LocalDateTime.now());
                head.setUpdatedBy("PAYMENT");
                saveHead(head);
            }
        }
    }

    @Override
    @Transactional(transactionManager = "invTransactionManager")
    public String issueByFulfillmentRef(String fulfillmentRef, String operator) {
        if (fulfillmentRef == null || fulfillmentRef.isBlank()) throw BizException.of(ErrorCode.BAD_REQUEST);
        var locked = lockFulfillment(fulfillmentRef);
        List<InvReservationLine> matching = locked.lines();
        String firstOutbound = matching.stream().map(InvReservationLine::getOutboundNo).filter(java.util.Objects::nonNull)
                .findFirst().orElse(null);
        for (InvReservationLine line : matching) {
            int issuedQty = line.getIssuedQty() == null ? 0 : line.getIssuedQty();
            int releasedQty = line.getReleasedQty() == null ? 0 : line.getReleasedQty();
            if (issuedQty + releasedQty >= line.getQty()) continue;
            InvReservation head = locked.heads().get(line.getReservationId());
            if (head == null || !(InvEnums.ReservationStatus.ALLOCATED.equals(head.getStatus())
                    || InvEnums.ReservationStatus.PARTIALLY_COMMITTED.equals(head.getStatus()))) continue;
            int remaining = line.getQty() - issuedQty - releasedQty;
            trackedChanged(
                    head,
                    balanceMapper.unhold(
                            head.getOwnerId(), line.getItemId(), line.getLocationId(), remaining));
            String outboundNo = outbound.postDirectly(new OutboundService.Draft(head.getOwnerId(), line.getLocationId(),
                    InvEnums.OutboundPurpose.SALE, head.getExternalRef(), head.getReservationId(), null,
                    LocalDateTime.now(), null, List.of(new OutboundService.Line(line.getItemId(), remaining, null))), operator);
            line.setIssuedQty(line.getQty());
            line.setOutboundNo(outboundNo);
            line.setIssuedAt(LocalDateTime.now());
            line.setUpdatedBy(operator);
            trackedChanged(head, lineMapper.updateById(line));
            if (firstOutbound == null) firstOutbound = outboundNo;
            List<InvReservationLine> all = linesOf(head.getReservationId());
            boolean done = all.stream().allMatch(row -> nz(row.getIssuedQty()) + nz(row.getReleasedQty()) >= row.getQty());
            boolean hasIssued = all.stream().anyMatch(row -> row.getIssuedQty() != null && row.getIssuedQty() > 0);
            boolean hasReleased = all.stream().anyMatch(row -> row.getReleasedQty() != null && row.getReleasedQty() > 0);
            head.setStatus(done ? hasReleased && hasIssued ? "PARTIAL_RELEASED"
                    : hasIssued ? InvEnums.ReservationStatus.COMMITTED : InvEnums.ReservationStatus.RELEASED
                    : hasIssued ? InvEnums.ReservationStatus.PARTIALLY_COMMITTED : InvEnums.ReservationStatus.ALLOCATED);
            if (head.getOutboundNo() == null) head.setOutboundNo(outboundNo);
            saveHead(head);
        }
        return firstOutbound;
    }

    @Override
    @Transactional(transactionManager = "invTransactionManager")
    public void releaseAllocatedByRef(String externalRef) {
        for (InvReservation head : allByRef(externalRef)) {
            releaseAllocatedLines(head, linesOf(head.getReservationId()));
        }
    }

    @Override
    @Transactional(transactionManager = "invTransactionManager")
    public void releaseAllocatedByFulfillmentRef(String fulfillmentRef) {
        var locked = lockFulfillment(fulfillmentRef);
        List<InvReservationLine> matching = locked.lines();
        for (InvReservation head : locked.heads().values()) {
            var requested = matching.stream()
                    .filter(line -> head.getReservationId().equals(line.getReservationId())).toList();
            if (!requested.isEmpty()) releaseAllocatedLines(head, requested);
        }
    }

    private void releaseAllocatedLines(InvReservation head, List<InvReservationLine> requested) {
        if (!(InvEnums.ReservationStatus.ALLOCATED.equals(head.getStatus())
                || InvEnums.ReservationStatus.PARTIALLY_COMMITTED.equals(head.getStatus()))) return;
        for (InvReservationLine line : requested) {
            int issued = nz(line.getIssuedQty());
            int released = nz(line.getReleasedQty());
            int remaining = line.getQty() - issued - released;
            if (remaining > 0) {
                trackedChanged(
                        head,
                        balanceMapper.unhold(
                                head.getOwnerId(),
                                line.getItemId(),
                                line.getLocationId(),
                                remaining));
                line.setReleasedQty(released + remaining);
                line.setUpdatedBy("REFUND");
                trackedChanged(head, lineMapper.updateById(line));
            }
        }
        List<InvReservationLine> all = linesOf(head.getReservationId());
        boolean settled = all.stream().allMatch(row -> nz(row.getIssuedQty()) + nz(row.getReleasedQty()) >= row.getQty());
        boolean hasIssued = all.stream().anyMatch(row -> nz(row.getIssuedQty()) > 0);
        boolean hasReleased = all.stream().anyMatch(row -> nz(row.getReleasedQty()) > 0);
        head.setStatus(settled ? hasIssued && hasReleased ? "PARTIAL_RELEASED"
                : hasIssued ? InvEnums.ReservationStatus.COMMITTED : InvEnums.ReservationStatus.RELEASED
                : hasIssued ? InvEnums.ReservationStatus.PARTIALLY_COMMITTED : InvEnums.ReservationStatus.ALLOCATED);
        head.setReleasedAt(LocalDateTime.now());
        head.setUpdatedBy("REFUND");
        saveHead(head);
    }

    private static int nz(Integer value) { return value == null ? 0 : value; }

    @Override
    @Transactional(transactionManager = "invTransactionManager")
    public void release(String ownerId, String externalRef) {
        InvReservation head = find(ownerId, externalRef);
        // 只作用于 HELD：重复释放不会把预留减两次（超时任务与用户取消会撞车）
        if (head == null || !InvEnums.ReservationStatus.HELD.equals(head.getStatus())) {
            return;
        }
        unholdAll(head, InvEnums.ReservationStatus.RELEASED);
    }

    @Override
    @Transactional(transactionManager = "invTransactionManager")
    public void releaseByRef(String externalRef) {
        for (InvReservation head : allByRef(externalRef)) {
            release(head.getOwnerId(), externalRef);
        }
    }

    @Override
    @Transactional(transactionManager = "invTransactionManager")
    public void cancelByRef(String ownerId, String externalRef) {
        InvReservation head = find(ownerId, externalRef);
        if (head != null) {
            if (InvEnums.ReservationStatus.COMMITTED.equals(head.getStatus())) {
                throw BizException.of(ErrorCode.CONFLICT);
            }
            release(ownerId, externalRef);
            return;
        }
        InvReservation tombstone = new InvReservation();
        tombstone.setReservationId(InvKeys.next(InvKeys.RESERVATION));
        tombstone.setOwnerId(ownerId);
        tombstone.setExternalRef(externalRef);
        tombstone.setStatus(InvEnums.ReservationStatus.RELEASED);
        tombstone.setExpiresAt(LocalDateTime.now());
        tombstone.setReleasedAt(LocalDateTime.now());
        resMapper.insert(tombstone);
    }

    @Override
    @Transactional(transactionManager = "invTransactionManager")
    public String commitByRef(String externalRef, String operator) {
        String firstOutbound = null;
        for (InvReservation head : allByRef(externalRef)) {
            String outboundNo = commit(head.getOwnerId(), externalRef, operator);
            if (firstOutbound == null) {
                firstOutbound = outboundNo;
            }
        }
        return firstOutbound;
    }

    @Override
    @Transactional(transactionManager = "invTransactionManager")
    public String restore(String ownerId, String afterSaleNo, List<Line> lines, String operator) {
        if (lines == null || lines.isEmpty()) {
            throw BizException.of(ErrorCode.BAD_REQUEST);
        }
        List<InboundService.Line> inLines = new ArrayList<>();
        for (Line l : lines) {
            inLines.add(new InboundService.Line(l.itemId(), l.qty(), null, null));
        }
        return inbound.postDirectly(new InboundService.Draft(
                ownerId, lines.get(0).locationId(), InvEnums.InboundSource.RETURN, afterSaleNo,
                // 系统自开的退货入库：没有供应商，supplierNo / supplierName 都是 null
                null, null, LocalDateTime.now(), null, inLines), operator);
    }

    @Override
    @Transactional(transactionManager = "invTransactionManager")
    public int expireOverdue(int limit) {
        LocalDateTime now = LocalDateTime.now();
        List<InvReservation> candidates = resMapper.selectList(Wrappers.<InvReservation>lambdaQuery()
                .eq(InvReservation::getStatus, InvEnums.ReservationStatus.HELD)
                .lt(InvReservation::getExpiresAt, now)
                .select(InvReservation::getOwnerId, InvReservation::getReservationId)
                .last("LIMIT " + Math.min(limit, EXPIRE_BATCH_MAX)));
        int expired = 0;
        // Lock the entire selected batch before any lines: an original observer may span
        // several of these heads. Payment can change HELD while the candidate scan waits.
        for (InvReservation current : lockHeads(candidates)) {
            if (current == null || !InvEnums.ReservationStatus.HELD.equals(current.getStatus())
                    || current.getExpiresAt() == null || !current.getExpiresAt().isBefore(now)) continue;
            unholdAll(current, InvEnums.ReservationStatus.EXPIRED);
            expired++;
        }
        return expired;
    }

    @Override
    public int countOverdue() {
        return Math.toIntExact(resMapper.selectCount(Wrappers.<InvReservation>lambdaQuery()
                .eq(InvReservation::getStatus, InvEnums.ReservationStatus.HELD)
                .lt(InvReservation::getExpiresAt, LocalDateTime.now())));
    }

    // ────────────────────────────────────────────────────────────────────

    private void unholdAll(InvReservation head, String toStatus) {
        if (!InvEnums.ReservationStatus.HELD.equals(head.getStatus())) return;
        var originalLines = linesOf(head.getReservationId());
        if (originalLines.isEmpty()) throw BizException.of(ErrorCode.CONFLICT);
        for (InvReservationLine line : originalLines) {
            if (line.getQty() == null
                    || line.getQty() <= 0
                    || nz(line.getIssuedQty()) != 0
                    || nz(line.getReleasedQty()) != 0) {
                throw BizException.of(ErrorCode.CONFLICT);
            }
            if (balanceMapper.unhold(
                            head.getOwnerId(),
                            line.getItemId(),
                            line.getLocationId(),
                            line.getQty())
                    != 1) {
                throw BizException.of(ErrorCode.CONFLICT);
            }
            line.setReleasedQty(line.getQty());
            if (lineMapper.updateById(line) != 1) throw BizException.of(ErrorCode.CONFLICT);
        }
        head.setStatus(toStatus);
        head.setReleasedAt(LocalDateTime.now());
        if (saveHead(head) != 1) throw BizException.of(ErrorCode.CONFLICT);
    }

    private List<InvReservationLine> linesOf(String reservationId) {
        // Every caller already owns this head. Use a current read after waiting for it,
        // rather than a repeatable-read snapshot established during discovery.
        return lineMapper.lockOriginal(reservationId);
    }

    /** Lock current identities in canonical HEAD order, preserving native reference equality. */
    private LockedFulfillment lockFulfillment(String fulfillmentRef) {
        var heads = new LinkedHashMap<String, InvReservation>();
        // The head-driving current join can lock each matching line after its OWN head,
        // before the next head. All heads advance monotonically; posting starts only once
        // the whole scan finishes. A plain discovery read could omit newly committed roots.
        for (InvReservation head : resMapper.lockByFulfillment(fulfillmentRef)) {
            heads.putIfAbsent(head.getReservationId(), head);
        }
        var matching = new ArrayList<InvReservationLine>();
        for (InvReservation head : heads.values()) {
            // Keep the original SQL collation semantics; Java equality would silently drop
            // e.g. a case-variant fulfillment reference that the native database matches.
            matching.addAll(lineMapper.selectList(Wrappers.<InvReservationLine>lambdaQuery()
                    .eq(InvReservationLine::getOwnerId, head.getOwnerId())
                    .eq(InvReservationLine::getReservationId, head.getReservationId())
                    .eq(InvReservationLine::getFulfillmentRef, fulfillmentRef)
                    .orderByAsc(InvReservationLine::getLineNo).last("FOR UPDATE")));
        }
        // Preserve the existing issue/outbound selection order after acquiring canonical locks.
        matching.sort(Comparator.comparing(InvReservationLine::getReservationId)
                .thenComparing(InvReservationLine::getLineNo));
        return new LockedFulfillment(heads, matching);
    }

    private List<InvReservation> lockHeads(List<InvReservation> candidates) {
        var keys = candidates.stream()
                .map(head -> new ReservationKey(head.getOwnerId(), head.getReservationId()))
                .distinct()
                .sorted(Comparator.comparing(ReservationKey::ownerId).thenComparing(ReservationKey::reservationId))
                .toList();
        var locked = new ArrayList<InvReservation>();
        for (var key : keys) {
            // Point lookups through the existing unique owner/reservation index avoid relying
            // on the optimizer's filesort/scan order to establish the cross-head lock order.
            var head = resMapper.selectOne(Wrappers.<InvReservation>lambdaQuery()
                    .eq(InvReservation::getOwnerId, key.ownerId())
                    .eq(InvReservation::getReservationId, key.reservationId()).last("FOR UPDATE"));
            if (head != null) locked.add(head);
        }
        return locked;
    }

    private record ReservationKey(String ownerId, String reservationId) {}

    private record LockedFulfillment(
            Map<String, InvReservation> heads, List<InvReservationLine> lines) {}

    private static String sourceManifest(List<Line> lines, int lifecycle) {
        if (lines == null
                || lines.isEmpty()
                || lines.stream().allMatch(line -> line.sourceSkuNo() == null)) {
            return null;
        }
        if (lines.stream()
                .anyMatch(
                        line ->
                                line.sourceSkuNo() == null
                                        || line.fulfillmentRef() == null
                                        || line.qty() <= 0)) {
            throw BizException.of(ErrorCode.BAD_REQUEST);
        }
        return StockDispositionContract.manifestHash(
                StockDispositionContract.needs(
                        lines.stream()
                                .map(
                                        line ->
                                                new StockPort.SkuQty(
                                                        line.sourceSkuNo(),
                                                        line.qty(),
                                                        line.sourceStoreNo(),
                                                        line.fulfillmentRef(),
                                                        lifecycle))
                                .toList()));
    }

    private static boolean sameSourceLines(List<Line> requested, List<InvReservationLine> saved) {
        var actual =
                saved.stream()
                        .map(
                                row ->
                                        new Line(
                                                row.getItemId(),
                                                row.getLocationId(),
                                                row.getQty(),
                                                row.getFulfillmentRef(),
                                                row.getSourceSkuNo(),
                                                row.getSourceStoreNo()))
                        .toList();
        return requested.size() == actual.size()
                && requested.stream()
                        .allMatch(
                                line ->
                                        java.util.Collections.frequency(requested, line)
                                                == java.util.Collections.frequency(actual, line));
    }

    private void requireProtected(InvReservation head, List<InvReservationLine> rows) {
        var totals = new java.util.TreeMap<String, Integer>();
        for (var row : rows) {
            totals.merge(row.getItemId() + ":" + row.getLocationId(), row.getQty(), Math::addExact);
        }
        for (var row : rows) {
            var balance =
                    balanceMapper.selectOne(
                            Wrappers.<InvStockBalance>lambdaQuery()
                                    .eq(InvStockBalance::getOwnerId, head.getOwnerId())
                                    .eq(InvStockBalance::getItemId, row.getItemId())
                                    .eq(InvStockBalance::getLocationId, row.getLocationId())
                                    .last("FOR UPDATE"));
            int need = totals.get(row.getItemId() + ":" + row.getLocationId());
            if (balance == null || balance.getReserved() < need || balance.getOnHand() < need) {
                throw BizException.of(ErrorCode.CONFLICT);
            }
        }
    }

    private int saveHead(InvReservation head) {
        if (head.getSourceManifestHash() != null) {
            if (head.getDispositionRevision() == null || head.getDispositionRevision() < 1) {
                throw BizException.of(ErrorCode.CONFLICT);
            }
            head.setDispositionRevision(Math.addExact(head.getDispositionRevision(), 1));
        }
        int affected = resMapper.updateById(head);
        trackedChanged(head, affected);
        return affected;
    }

    private static void trackedChanged(InvReservation head, int affected) {
        if (head.getSourceManifestHash() != null
                && head.getConfirmationOperationNo() != null
                && affected != 1) {
            throw BizException.of(ErrorCode.CONFLICT);
        }
    }

    /** 按单号反查（订单号平台内全局唯一）。 */
    private List<InvReservation> allByRef(String externalRef) {
        return resMapper.lockOriginal(externalRef);
    }

    private InvReservation find(String ownerId, String externalRef) {
        return resMapper.selectOne(Wrappers.<InvReservation>lambdaQuery()
                .eq(InvReservation::getOwnerId, ownerId)
                .eq(InvReservation::getExternalRef, externalRef).last("FOR UPDATE"));
    }

    private InvReservation require(String ownerId, String externalRef) {
        InvReservation head = find(ownerId, externalRef);
        if (head == null) {
            throw BizException.of(ErrorCode.NOT_FOUND);
        }
        return head;
    }
}
