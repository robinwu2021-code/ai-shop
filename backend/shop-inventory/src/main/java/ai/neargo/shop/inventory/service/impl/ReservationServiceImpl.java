package ai.neargo.shop.inventory.service.impl;

import ai.neargo.shop.inventory.config.ConditionalOnInventory;
import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.inventory.entity.InvReservation;
import ai.neargo.shop.inventory.entity.InvReservationLine;
import ai.neargo.shop.inventory.mapper.InventoryMappers.BalanceMapper;
import ai.neargo.shop.inventory.mapper.InventoryMappers.ReservationLineMapper;
import ai.neargo.shop.inventory.mapper.InventoryMappers.ReservationMapper;
import ai.neargo.shop.inventory.service.InboundService;
import ai.neargo.shop.inventory.service.OutboundService;
import ai.neargo.shop.inventory.service.ReservationService;
import ai.neargo.shop.inventory.support.InvEnums;
import ai.neargo.shop.inventory.support.InvKeys;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Objects;

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
        if (lifecycleVersion != 1 && lifecycleVersion != 2 && lifecycleVersion != 3) {
            throw BizException.of(ErrorCode.BAD_REQUEST);
        }
        if (ownerId == null || ownerId.isBlank() || externalRef == null || externalRef.isBlank()) {
            throw BizException.of(ErrorCode.BAD_REQUEST);
        }
        InvReservation exists = find(ownerId, externalRef);
        if (exists != null) {
            if (InvEnums.ReservationStatus.RELEASED.equals(exists.getStatus())
                    || InvEnums.ReservationStatus.EXPIRED.equals(exists.getStatus())) {
                throw BizException.of(ErrorCode.CONFLICT);
            }
            Map<LineKey, Integer> original = normalize(linesOf(exists.getReservationId()).stream()
                    .map(row -> new Line(row.getItemId(), row.getLocationId(), row.getQty(),
                            row.getFulfillmentRef())).toList(), ErrorCode.CONFLICT);
            if (lifecycle(exists) != lifecycleVersion || !original.equals(normalize(lines, ErrorCode.CONFLICT))) {
                throw BizException.of(ErrorCode.CONFLICT);
            }
            return exists.getReservationId();
        }
        Map<LineKey, Integer> normalized = normalize(lines, ErrorCode.BAD_REQUEST);
        lines = normalized.entrySet().stream().map(entry -> new Line(entry.getKey().itemId(),
                entry.getKey().locationId(), entry.getValue(), entry.getKey().fulfillmentRef())).toList();
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
            row.setIssuedQty(0);
            lineMapper.insert(row);
        }
        return id;
    }

    @Override
    @Transactional(transactionManager = "invTransactionManager", readOnly = true)
    public Optional<Snapshot> query(String ownerId, String externalRef) {
        InvReservation head = resMapper.selectOne(Wrappers.<InvReservation>lambdaQuery()
                .eq(InvReservation::getOwnerId, ownerId).eq(InvReservation::getExternalRef, externalRef));
        if (head == null) return Optional.empty();
        Status status = InvEnums.ReservationStatus.PARTIALLY_COMMITTED.equals(head.getStatus())
                ? Status.PARTIALLY_COMMITTED : Status.valueOf(head.getStatus());
        List<Line> original = linesOf(head.getReservationId()).stream()
                .map(row -> new Line(row.getItemId(), row.getLocationId(), row.getQty(), row.getFulfillmentRef()))
                .toList();
        return Optional.of(new Snapshot(head.getReservationId(), status, lifecycle(head),
                head.getCommittedAt() != null, original));
    }

    @Override
    @Transactional(transactionManager = "invTransactionManager")
    public void acceptUnpaid(String ownerId, String externalRef) {
        InvReservation head = find(ownerId, externalRef);
        if (head == null || lifecycle(head) != 3 || InvEnums.ReservationStatus.RELEASED.equals(head.getStatus())
                || InvEnums.ReservationStatus.EXPIRED.equals(head.getStatus())) {
            throw BizException.of(ErrorCode.CONFLICT);
        }
        if (InvEnums.ReservationStatus.HELD.equals(head.getStatus())) {
            head.setStatus(InvEnums.ReservationStatus.ACCEPTED_UNPAID);
            head.setUpdatedBy("ACCEPT_UNPAID");
            resMapper.updateById(head);
        }
    }

    private void acknowledgePostpaidPayment(InvReservation head) {
        if (InvEnums.ReservationStatus.RELEASED.equals(head.getStatus())
                || InvEnums.ReservationStatus.EXPIRED.equals(head.getStatus())) {
            throw BizException.of(ErrorCode.CONFLICT);
        }
        if (head.getCommittedAt() == null) {
            // For credit orders this existing timestamp records payment, never physical issue or acceptance.
            head.setCommittedAt(LocalDateTime.now());
            setPostpaidProgress(head, linesOf(head.getReservationId()));
            head.setUpdatedBy("PAYMENT");
            resMapper.updateById(head);
        }
    }

    private void setPostpaidProgress(InvReservation head, List<InvReservationLine> all) {
        boolean settled = all.stream().allMatch(row -> nz(row.getIssuedQty()) + nz(row.getReleasedQty())
                >= row.getQty());
        boolean issued = all.stream().anyMatch(row -> nz(row.getIssuedQty()) > 0);
        boolean released = all.stream().anyMatch(row -> nz(row.getReleasedQty()) > 0);
        boolean paid = head.getCommittedAt() != null;
        if (settled) {
            head.setStatus(issued && released ? "PARTIAL_RELEASED" : issued
                    ? paid ? InvEnums.ReservationStatus.COMMITTED : InvEnums.ReservationStatus.ISSUED_UNPAID
                    : InvEnums.ReservationStatus.RELEASED);
        } else if (issued) {
            head.setStatus(paid ? InvEnums.ReservationStatus.PARTIALLY_COMMITTED
                    : InvEnums.ReservationStatus.PARTIAL_UNPAID);
        } else if (paid) {
            head.setStatus(InvEnums.ReservationStatus.ALLOCATED);
        } else if (!InvEnums.ReservationStatus.HELD.equals(head.getStatus())) {
            head.setStatus(InvEnums.ReservationStatus.ACCEPTED_UNPAID);
        }
    }

    private static int lifecycle(InvReservation head) {
        // Rows created before lifecycle versioning are the original payment-deduct protocol.
        return head.getLifecycleVersion() == null ? 1 : head.getLifecycleVersion();
    }

    private static Map<LineKey, Integer> normalize(List<Line> lines, ErrorCode error) {
        if (lines == null || lines.isEmpty()) throw BizException.of(error);
        Map<LineKey, Integer> result = new LinkedHashMap<>();
        for (Line line : lines) {
            if (line == null || line.itemId() == null || line.itemId().isBlank()
                    || line.locationId() == null || line.locationId().isBlank() || line.qty() <= 0) {
                throw BizException.of(error);
            }
            try {
                result.merge(new LineKey(line.itemId(), line.locationId(), line.fulfillmentRef()),
                        line.qty(), Math::addExact);
            } catch (ArithmeticException overflow) {
                throw BizException.of(error);
            }
        }
        return result;
    }

    private record LineKey(String itemId, String locationId, String fulfillmentRef) { }

    @Override
    @Transactional(transactionManager = "invTransactionManager")
    public String commit(String ownerId, String externalRef, String operator) {
        InvReservation head = require(ownerId, externalRef);
        if (lifecycle(head) == 3) {
            acknowledgePostpaidPayment(head);
            return head.getOutboundNo();
        }
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
            balanceMapper.unhold(ownerId, r.getItemId(), r.getLocationId(), r.getQty());
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
        resMapper.updateById(head);
        return outboundNo;
    }

    @Override
    @Transactional(transactionManager = "invTransactionManager")
    public void allocatePaidByRef(String externalRef) {
        for (InvReservation head : allByRef(externalRef)) {
            if (lifecycle(head) == 3) {
                acknowledgePostpaidPayment(head);
                continue;
            }
            if (Integer.valueOf(2).equals(head.getLifecycleVersion())
                    && InvEnums.ReservationStatus.HELD.equals(head.getStatus())) {
                head.setStatus(InvEnums.ReservationStatus.ALLOCATED);
                head.setCommittedAt(LocalDateTime.now());
                // ALLOCATED is excluded from expiry; this timestamp remains only for compatibility/readers.
                head.setExpiresAt(LocalDateTime.now());
                head.setUpdatedBy("PAYMENT");
                resMapper.updateById(head);
            }
        }
    }

    @Override
    @Transactional(transactionManager = "invTransactionManager")
    public void allocatePaid(String ownerId, String externalRef) {
        InvReservation head = require(ownerId, externalRef);
        if (lifecycle(head) == 3) {
            acknowledgePostpaidPayment(head);
        } else if (lifecycle(head) == 2) {
            if (InvEnums.ReservationStatus.HELD.equals(head.getStatus())) {
                head.setStatus(InvEnums.ReservationStatus.ALLOCATED);
                head.setCommittedAt(LocalDateTime.now());
                head.setExpiresAt(LocalDateTime.now());
                head.setUpdatedBy("PAYMENT");
                resMapper.updateById(head);
            } else if (!List.of(InvEnums.ReservationStatus.ALLOCATED,
                    InvEnums.ReservationStatus.PARTIALLY_COMMITTED, InvEnums.ReservationStatus.COMMITTED,
                    "PARTIAL_RELEASED").contains(head.getStatus())) {
                throw BizException.of(ErrorCode.CONFLICT);
            }
        } else {
            throw BizException.of(ErrorCode.CONFLICT);
        }
    }

    @Override
    @Transactional(transactionManager = "invTransactionManager")
    public String issue(String ownerId, String externalRef, String fulfillmentRef, String operator) {
        if (fulfillmentRef == null || fulfillmentRef.isBlank()) throw BizException.of(ErrorCode.BAD_REQUEST);
        InvReservation head = require(ownerId, externalRef);
        List<InvReservationLine> matching = lineMapper.selectList(Wrappers.<InvReservationLine>lambdaQuery()
                .eq(InvReservationLine::getReservationId, head.getReservationId())
                .eq(InvReservationLine::getOwnerId, ownerId)
                .eq(InvReservationLine::getFulfillmentRef, fulfillmentRef)
                .orderByAsc(InvReservationLine::getLineNo).last("FOR UPDATE"));
        return issueMatching(matching, operator);
    }

    @Override
    @Transactional(transactionManager = "invTransactionManager")
    public String issueByFulfillmentRef(String fulfillmentRef, String operator) {
        if (fulfillmentRef == null || fulfillmentRef.isBlank()) throw BizException.of(ErrorCode.BAD_REQUEST);
        List<InvReservationLine> matching = lineMapper.selectList(Wrappers.<InvReservationLine>lambdaQuery()
                .eq(InvReservationLine::getFulfillmentRef, fulfillmentRef).orderByAsc(InvReservationLine::getReservationId)
                .orderByAsc(InvReservationLine::getLineNo).last("FOR UPDATE"));
        return issueMatching(matching, operator);
    }

    private String issueMatching(List<InvReservationLine> matching, String operator) {
        String firstOutbound = matching.stream().map(InvReservationLine::getOutboundNo).filter(java.util.Objects::nonNull)
                .findFirst().orElse(null);
        for (InvReservationLine line : matching) {
            int issuedQty = line.getIssuedQty() == null ? 0 : line.getIssuedQty();
            int releasedQty = line.getReleasedQty() == null ? 0 : line.getReleasedQty();
            if (issuedQty + releasedQty >= line.getQty()) continue;
            InvReservation head = resMapper.selectOne(Wrappers.<InvReservation>lambdaQuery()
                    .eq(InvReservation::getReservationId, line.getReservationId()).last("FOR UPDATE"));
            if (head == null || !(InvEnums.ReservationStatus.ALLOCATED.equals(head.getStatus())
                    || InvEnums.ReservationStatus.PARTIALLY_COMMITTED.equals(head.getStatus())
                    || lifecycle(head) == 3 && (InvEnums.ReservationStatus.ACCEPTED_UNPAID.equals(head.getStatus())
                    || InvEnums.ReservationStatus.PARTIAL_UNPAID.equals(head.getStatus())))) continue;
            int remaining = line.getQty() - issuedQty - releasedQty;
            balanceMapper.unhold(head.getOwnerId(), line.getItemId(), line.getLocationId(), remaining);
            String outboundNo = outbound.postDirectly(new OutboundService.Draft(head.getOwnerId(), line.getLocationId(),
                    InvEnums.OutboundPurpose.SALE, head.getExternalRef(), head.getReservationId(), null,
                    LocalDateTime.now(), null, List.of(new OutboundService.Line(line.getItemId(), remaining, null))), operator);
            line.setIssuedQty(issuedQty + remaining);
            line.setOutboundNo(outboundNo);
            line.setIssuedAt(LocalDateTime.now());
            line.setUpdatedBy(operator);
            lineMapper.updateById(line);
            if (firstOutbound == null) firstOutbound = outboundNo;
            List<InvReservationLine> all = linesOf(head.getReservationId());
            boolean done = all.stream().allMatch(row -> nz(row.getIssuedQty()) + nz(row.getReleasedQty()) >= row.getQty());
            boolean hasIssued = all.stream().anyMatch(row -> row.getIssuedQty() != null && row.getIssuedQty() > 0);
            boolean hasReleased = all.stream().anyMatch(row -> row.getReleasedQty() != null && row.getReleasedQty() > 0);
            head.setStatus(done ? hasReleased && hasIssued ? "PARTIAL_RELEASED"
                    : hasIssued ? InvEnums.ReservationStatus.COMMITTED : InvEnums.ReservationStatus.RELEASED
                    : hasIssued ? InvEnums.ReservationStatus.PARTIALLY_COMMITTED : InvEnums.ReservationStatus.ALLOCATED);
            if (lifecycle(head) == 3) setPostpaidProgress(head, all);
            if (head.getOutboundNo() == null) head.setOutboundNo(outboundNo);
            resMapper.updateById(head);
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
        List<InvReservationLine> matching = lineMapper.selectList(Wrappers.<InvReservationLine>lambdaQuery()
                .eq(InvReservationLine::getFulfillmentRef, fulfillmentRef).orderByAsc(InvReservationLine::getReservationId)
                .orderByAsc(InvReservationLine::getLineNo).last("FOR UPDATE"));
        java.util.Map<String, List<InvReservationLine>> byReservation = matching.stream()
                .collect(java.util.stream.Collectors.groupingBy(InvReservationLine::getReservationId));
        for (var entry : byReservation.entrySet()) {
            InvReservation head = resMapper.selectOne(Wrappers.<InvReservation>lambdaQuery()
                    .eq(InvReservation::getReservationId, entry.getKey()).last("FOR UPDATE"));
            if (head != null) releaseAllocatedLines(head, entry.getValue());
        }
    }

    @Override
    @Transactional(transactionManager = "invTransactionManager")
    public void releaseAllocatedTo(String ownerId, String externalRef, String fulfillmentRef, List<ReleaseTarget> targets) {
        if (ownerId == null || externalRef == null || fulfillmentRef == null || targets == null || targets.isEmpty())
            throw BizException.of(ErrorCode.BAD_REQUEST);
        InvReservation head = require(ownerId, externalRef);
        if (lifecycle(head) < 2 || !Objects.equals(ownerId, head.getOwnerId())
                || !Objects.equals(externalRef, head.getExternalRef())) throw BizException.of(ErrorCode.CONFLICT);
        List<InvReservationLine> original = lineMapper.selectList(Wrappers.<InvReservationLine>lambdaQuery()
                .eq(InvReservationLine::getReservationId, head.getReservationId())
                .eq(InvReservationLine::getFulfillmentRef, fulfillmentRef)
                .orderByAsc(InvReservationLine::getLineNo).last("FOR UPDATE"));
        if (original.stream().anyMatch(line -> !Objects.equals(line.getReservationId(), head.getReservationId())
                || !Objects.equals(line.getOwnerId(), ownerId)
                || !Objects.equals(line.getFulfillmentRef(), fulfillmentRef))) throw BizException.of(ErrorCode.CONFLICT);
        Map<String, Integer> wanted = new LinkedHashMap<>();
        for (ReleaseTarget target : targets) {
            if (target.itemId() == null || target.itemId().isBlank() || target.cumulativeQty() <= 0)
                throw BizException.of(ErrorCode.BAD_REQUEST);
            wanted.merge(target.itemId(), target.cumulativeQty(), Math::addExact);
        }
        for (var target : wanted.entrySet()) {
            var rows = original.stream().filter(line -> target.getKey().equals(line.getItemId())).toList();
            int cap = rows.stream().mapToInt(line -> line.getQty() - nz(line.getIssuedQty())).sum();
            int released = rows.stream().mapToInt(line -> nz(line.getReleasedQty())).sum();
            if (rows.isEmpty() || target.getValue() > cap || released > cap) throw BizException.of(ErrorCode.CONFLICT);
            int delta = Math.max(0, target.getValue() - released);
            if (delta > 0 && !(InvEnums.ReservationStatus.ALLOCATED.equals(head.getStatus())
                    || InvEnums.ReservationStatus.PARTIALLY_COMMITTED.equals(head.getStatus())
                    || lifecycle(head) == 3 && (InvEnums.ReservationStatus.ACCEPTED_UNPAID.equals(head.getStatus())
                            || InvEnums.ReservationStatus.PARTIAL_UNPAID.equals(head.getStatus()))))
                throw BizException.of(ErrorCode.CONFLICT);
            for (var line : rows) {
                int quantity = Math.min(delta, line.getQty() - nz(line.getIssuedQty()) - nz(line.getReleasedQty()));
                if (quantity > 0) {
                    if (balanceMapper.unhold(head.getOwnerId(), line.getItemId(), line.getLocationId(), quantity) != 1)
                        throw BizException.of(ErrorCode.CONFLICT);
                    line.setReleasedQty(nz(line.getReleasedQty()) + quantity);
                    line.setUpdatedBy("REFUND");
                    if (lineMapper.updateById(line) != 1) throw BizException.of(ErrorCode.CONFLICT);
                    delta -= quantity;
                }
            }
            if (delta != 0) throw BizException.of(ErrorCode.CONFLICT);
        }
        var all = linesOf(head.getReservationId());
        boolean settled = all.stream().allMatch(line -> nz(line.getIssuedQty()) + nz(line.getReleasedQty()) == line.getQty());
        boolean issued = all.stream().anyMatch(line -> nz(line.getIssuedQty()) > 0);
        boolean released = all.stream().anyMatch(line -> nz(line.getReleasedQty()) > 0);
        if (lifecycle(head) == 3) setPostpaidProgress(head, all);
        else head.setStatus(settled ? issued && released ? "PARTIAL_RELEASED" : issued ? InvEnums.ReservationStatus.COMMITTED
                : InvEnums.ReservationStatus.RELEASED : issued ? InvEnums.ReservationStatus.PARTIALLY_COMMITTED : InvEnums.ReservationStatus.ALLOCATED);
        head.setReleasedAt(LocalDateTime.now()); head.setUpdatedBy("REFUND");
        if (resMapper.updateById(head) != 1) throw BizException.of(ErrorCode.CONFLICT);
    }

    private void releaseAllocatedLines(InvReservation head, List<InvReservationLine> requested) {
        if (!(InvEnums.ReservationStatus.ALLOCATED.equals(head.getStatus())
                || InvEnums.ReservationStatus.PARTIALLY_COMMITTED.equals(head.getStatus())
                || lifecycle(head) == 3 && (InvEnums.ReservationStatus.HELD.equals(head.getStatus())
                || InvEnums.ReservationStatus.ACCEPTED_UNPAID.equals(head.getStatus())
                || InvEnums.ReservationStatus.PARTIAL_UNPAID.equals(head.getStatus())))) return;
        for (InvReservationLine line : requested) {
            int issued = nz(line.getIssuedQty());
            int released = nz(line.getReleasedQty());
            int remaining = line.getQty() - issued - released;
            if (remaining > 0) {
                balanceMapper.unhold(head.getOwnerId(), line.getItemId(), line.getLocationId(), remaining);
                line.setReleasedQty(released + remaining);
                line.setUpdatedBy("REFUND");
                lineMapper.updateById(line);
            }
        }
        List<InvReservationLine> all = linesOf(head.getReservationId());
        boolean settled = all.stream().allMatch(row -> nz(row.getIssuedQty()) + nz(row.getReleasedQty()) >= row.getQty());
        boolean hasIssued = all.stream().anyMatch(row -> nz(row.getIssuedQty()) > 0);
        boolean hasReleased = all.stream().anyMatch(row -> nz(row.getReleasedQty()) > 0);
        if (lifecycle(head) == 3) {
            setPostpaidProgress(head, all);
        } else {
            head.setStatus(settled ? hasIssued && hasReleased ? "PARTIAL_RELEASED"
                    : hasIssued ? InvEnums.ReservationStatus.COMMITTED : InvEnums.ReservationStatus.RELEASED
                    : hasIssued ? InvEnums.ReservationStatus.PARTIALLY_COMMITTED : InvEnums.ReservationStatus.ALLOCATED);
        }
        head.setReleasedAt(LocalDateTime.now());
        head.setUpdatedBy("REFUND");
        resMapper.updateById(head);
    }

    private static int nz(Integer value) { return value == null ? 0 : value; }

    @Override
    @Transactional(transactionManager = "invTransactionManager")
    public void release(String ownerId, String externalRef) {
        InvReservation head = find(ownerId, externalRef);
        if (head != null && lifecycle(head) == 3) {
            releaseAllocatedLines(head, linesOf(head.getReservationId()));
            return;
        }
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
            if (lifecycle(head) != 3 && InvEnums.ReservationStatus.COMMITTED.equals(head.getStatus())) {
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
                .select(InvReservation::getReservationId)
                .last("LIMIT " + Math.min(limit, EXPIRE_BATCH_MAX)));
        int expired = 0;
        for (InvReservation candidate : candidates) {
            // The initial query is only a candidate scan. Re-read under the reservation row
            // lock because payment may have changed HELD to ALLOCATED in the meantime.
            InvReservation current = resMapper.selectOne(Wrappers.<InvReservation>lambdaQuery()
                    .eq(InvReservation::getReservationId, candidate.getReservationId()).last("FOR UPDATE"));
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
        for (InvReservationLine r : linesOf(head.getReservationId())) {
            int remaining = lifecycle(head) == 3 ? r.getQty() - nz(r.getIssuedQty()) - nz(r.getReleasedQty())
                    : r.getQty();
            if (remaining > 0) {
                balanceMapper.unhold(head.getOwnerId(), r.getItemId(), r.getLocationId(), remaining);
                if (lifecycle(head) == 3) {
                    r.setReleasedQty(nz(r.getReleasedQty()) + remaining);
                    r.setUpdatedBy(toStatus);
                    lineMapper.updateById(r);
                }
            }
        }
        head.setStatus(toStatus);
        head.setReleasedAt(LocalDateTime.now());
        resMapper.updateById(head);
    }

    private List<InvReservationLine> linesOf(String reservationId) {
        return lineMapper.selectList(Wrappers.<InvReservationLine>lambdaQuery()
                .eq(InvReservationLine::getReservationId, reservationId)
                .orderByAsc(InvReservationLine::getLineNo));
    }

    /** 按单号反查（订单号平台内全局唯一）。 */
    private List<InvReservation> allByRef(String externalRef) {
        return resMapper.selectList(Wrappers.<InvReservation>lambdaQuery()
                .eq(InvReservation::getExternalRef, externalRef).orderByAsc(InvReservation::getOwnerId)
                .last("FOR UPDATE"));
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
