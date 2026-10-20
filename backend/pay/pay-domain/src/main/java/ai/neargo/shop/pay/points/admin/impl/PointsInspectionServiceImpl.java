package ai.neargo.shop.pay.points.admin.impl;

import ai.neargo.shop.pay.points.admin.PointsInspectionService;
import ai.neargo.shop.pay.points.admin.PointsOperatorJournal;
import ai.neargo.shop.pay.points.admin.PointsOperatorJournal.OperatorResult;
import ai.neargo.shop.pay.points.lifecycle.PointsAccountControlService;
import ai.neargo.shop.pay.points.model.Bucket;
import ai.neargo.shop.pay.points.model.HolderType;
import ai.neargo.shop.pay.points.model.PointsKeys;
import ai.neargo.shop.pay.points.model.PositionMove;
import ai.neargo.shop.pay.points.query.PointsStatsService;
import ai.neargo.shop.pay.points.query.PointsStatsService.Snapshot;
import ai.neargo.shop.pay.points.recon.PointsReconService;
import ai.neargo.shop.pay.points.recon.PointsReconService.Report;
import ai.neargo.shop.pay.points.store.PointsOperationsStore;
import ai.neargo.shop.pay.points.store.PointsOperationsStore.Kind;
import ai.neargo.shop.pay.points.store.PointsReadStore;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import tools.jackson.databind.ObjectMapper;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Map;
import java.util.EnumMap;
import java.util.List;

@Service
public class PointsInspectionServiceImpl implements PointsInspectionService {
    @org.springframework.beans.factory.annotation.Autowired
    private ai.neargo.shop.pay.entitlement.service.PayRightsDocumentNumbers documentNumbers;
    private final PointsReadStore read;
    private final PointsOperationsStore store;
    private final PointsOperatorJournal journal;
    private final PointsAccountControlService accounts;
    private final PointsStatsService statistics;
    private final PointsReconService recon;
    private final Clock clock;
    private final ObjectMapper json = new ObjectMapper();

    public PointsInspectionServiceImpl(
            PointsReadStore read,
            PointsOperationsStore store,
            PointsOperatorJournal journal,
            PointsAccountControlService accounts,
            PointsStatsService statistics,
            PointsReconService recon,
            ObjectProvider<Clock> clocks) {
        this.read = read;
        this.store = store;
        this.journal = journal;
        this.accounts = accounts;
        this.statistics = statistics;
        this.recon = recon;
        this.clock = clocks.getIfAvailable(Clock::systemUTC);
    }

    @Override
    @Transactional(value = "payTxManager", readOnly = true)
    public AccountView account(String type, HolderType holder, String subject) {
        type(type);
        require(holder != null && nonblank(subject), "POINTS_ACCOUNT_INPUT_INVALID");
        var head = store.accountHead(type, holder, subject);
        return new AccountView(
                read.account(type, holder, subject),
                head != null,
                head == null ? -1 : num(head, "version"));
    }

    @Override
    @Transactional(value = "payTxManager", readOnly = true)
    public AccountPage accounts(String type, HolderType holder, String holderNo, long after, int limit) {
        page(type, after, limit);
        require(holder == HolderType.USER || holder == HolderType.STORE, "POINTS_ACCOUNT_HOLDER_INVALID");
        require(holderNo == null || !holderNo.isBlank(), "POINTS_ACCOUNT_INPUT_INVALID");
        var raw = store.accounts(type, holder, holderNo, after, limit + 1);
        boolean more = raw.size() > limit;
        var pageRows = more ? raw.subList(0, limit) : raw;
        var rows = pageRows.stream().map(r -> {
            var balances = new EnumMap<Bucket, Long>(Bucket.class);
            for (Bucket bucket : Bucket.values()) balances.put(bucket, num(r, bucket.column()));
            HolderType accountHolder = HolderType.valueOf(text(r, "holder_type"));
            var snapshot = new ai.neargo.shop.pay.points.model.AccountSnapshot(
                    text(r, "type_code"), accountHolder, text(r, "holder_no"), balances,
                    num(r, "frozen") == 1,
                    r.get("expire_at") == null ? null : num(r, "expire_at"),
                    num(r, "expiry_revision"), r.get("last_active_at") == null ? 0 : num(r, "last_active_at"));
            return new AccountView(snapshot, true, num(r, "version"));
        }).toList();
        long next = pageRows.isEmpty() ? after : num(pageRows.getLast(), "id");
        return new AccountPage(rows, next, more);
    }

    @Override
    @Transactional(value = "payTxManager", readOnly = true)
    public TransactionPage transactions(String type, TransactionDirection direction, LocalDate from,
            LocalDate to, String storeNo, String userNo, long after, int limit) {
        type(type);
        require(direction != null && from != null && to != null && !to.isBefore(from)
                && java.time.temporal.ChronoUnit.DAYS.between(from, to) <= 92, "POINTS_TRANSACTION_FILTER_INVALID");
        require(storeNo == null || !storeNo.isBlank(), "POINTS_TRANSACTION_FILTER_INVALID");
        require(userNo == null || !userNo.isBlank(), "POINTS_TRANSACTION_FILTER_INVALID");
        page(type, after, limit);
        var raw = store.transactionVouchers(type, direction.name(), from.atStartOfDay(),
                to.plusDays(1).atStartOfDay(), storeNo, userNo, after, limit + 1);
        boolean more = raw.size() > limit;
        var pageRows = more ? raw.subList(0, limit) : raw;
        var rows = pageRows.stream().map(r -> new TransactionRow(direction, voucher(r))).toList();
        long next = pageRows.isEmpty() ? after : num(pageRows.getLast(), "id");
        return new TransactionPage(rows, next, more);
    }

    @Override
    @Transactional(value = "payTxManager", readOnly = true)
    public TransactionDetail transaction(String type, String voucherNo) {
        type(type);
        require(nonblank(voucherNo), "POINTS_TRANSACTION_INPUT_INVALID");
        voucherNo=documentNumbers.resolve(ai.neargo.shop.numbering.DocumentType.POINTS_VOUCHER,"MAIN",voucherNo);
        var row = store.voucherByNo(type, voucherNo);
        require(row != null, "POINTS_VOUCHER_UNKNOWN");
        var direction = direction(text(row, "biz_ref"));
        require(direction != null, "POINTS_TRANSACTION_DIRECTION_UNSUPPORTED");
        var entries = entries(type, null, null, voucherNo, 0, 200);
        return new TransactionDetail(direction, voucher(row), entries);
    }

    private VoucherView voucher(Map<String, Object> r) {
        return new VoucherView(num(r, "id"), text(r, "voucher_no"), text(r, "biz_ref"),
                text(r, "currency"), num(r, "rate_points"), num(r, "rate_minor"), documentNumbers.find(ai.neargo.shop.numbering.DocumentType.POINTS_VOUCHER, "MAIN", text(r, "voucher_no")));
    }

    private TransactionDirection direction(String ref) {
        if (ref == null) return null;
        if (ref.startsWith("GRANT:")) return TransactionDirection.EARN;
        if (ref.startsWith("HOLD:") || ref.startsWith("RELEASE:") || ref.startsWith("FINALIZE:")) return TransactionDirection.REDEEM;
        if (ref.startsWith("CANCEL:") || ref.startsWith("REFUND_CLOSE:") || ref.startsWith("REFUND_CONFIRM:")) return TransactionDirection.REVERSAL;
        return null;
    }

    @Override
    public OperatorResult freeze(Freeze r, String actor) {
        return journal.apply(
                "ACCOUNT_FREEZE",
                r.requestId(),
                r.typeCode(),
                actor,
                r.reason(),
                r,
                () -> {
                    var head = store.accountHead(r.typeCode(), HolderType.USER, r.userNo());
                    require(head != null, "POINTS_ACCOUNT_UNKNOWN");
                    require(
                            num(head, "version") == r.expectedVersion(),
                            "POINTS_ACCOUNT_REVISION_CONFLICT");
                    accounts.freeze(
                            new PointsAccountControlService.Command(
                                    PointsKeys.position("OF", r.typeCode(), r.requestId()),
                                    r.typeCode(),
                                    r.userNo(),
                                    r.frozen(),
                                    clock.millis(),
                                    actor,
                                    r.reason()));
                    return r.userNo();
                });
    }

    @Override
    public VoucherPage vouchers(String type, long after, int limit) {
        page(type, after, limit);
        var rows = store.page(type, Kind.VOUCHER, after, limit).stream().map(this::voucher).toList();
        return new VoucherPage(
                rows, rows.isEmpty() ? after : rows.getLast().id(), rows.size() == limit);
    }

    @Override
    public EntryPage entries(
            String type, HolderType holder, String subject, String voucher, long after, int limit) {
        page(type, after, limit);
        require(
                nonblank(voucher)
                        ? holder == null && subject == null
                        : holder != null && nonblank(subject),
                "POINTS_ENTRY_FILTER_REQUIRED");
        voucher=documentNumbers.resolve(ai.neargo.shop.numbering.DocumentType.POINTS_VOUCHER,"MAIN",voucher);
        var raw=store.entries(type, holder, subject, voucher, after, limit);
        var aliases=documentNumbers.findAll(ai.neargo.shop.numbering.DocumentType.POINTS_VOUCHER,"MAIN",
                raw.stream().map(r -> text(r,"voucher_no")).toList());
        var rows = raw.stream()
                        .map(
                                r ->
                                        new EntryView(
                                                num(r, "id"),
                                                text(r, "entry_no"),
                                                text(r, "voucher_no"),
                                                HolderType.valueOf(text(r, "holder_type")),
                                                text(r, "holder_no"),
                                                Bucket.valueOf(text(r, "bucket")),
                                                num(r, "delta_points"),
                                                num(r, "after_points"),
                                                time(r, "created_at"),aliases.get(text(r,"voucher_no"))))
                        .toList();
        return new EntryPage(
                rows, rows.isEmpty() ? after : rows.getLast().id(), rows.size() == limit);
    }

    @Override
    public FlowPage flows(String type, long after, int limit) {
        page(type, after, limit);
        var raw=store.page(type, Kind.FLOW, after, limit);
        var aliases=documentNumbers.findAll(ai.neargo.shop.numbering.DocumentType.POINTS_VOUCHER,"MAIN",
                raw.stream().map(r -> text(r,"voucher_no")).toList());
        var rows = raw.stream()
                        .map(
                                r ->
                                        new FlowView(
                                                num(r, "id"),
                                                text(r, "flow_no"),
                                                text(r, "voucher_no"),
                                                FlowBucket.valueOf(text(r, "bucket")),
                                                num(r, "delta_minor"),
                                                num(r, "after_minor"),aliases.get(text(r,"voucher_no"))))
                        .toList();
        return new FlowPage(
                rows, rows.isEmpty() ? after : rows.getLast().id(), rows.size() == limit);
    }

    @Override
    @Transactional(value = "payTxManager", readOnly = true)
    public EarningView earning(String type, String subOrderNo) {
        type(type);
        require(nonblank(subOrderNo), "POINTS_EARN_INPUT_INVALID");
        var row = read.position(PositionMove.Kind.GRANT.table(), PointsKeys.position("G", type, subOrderNo));
        if (row == null) return null;
        var grant = json.readValue(text(row,"payload_json"), ai.neargo.shop.pay.points.earn.impl.EarnServiceImpl.GrantSnapshot.class);
        return new EarningView(text(row,"state"), grant.decision().expectedPoints(), grant.outcome().grantedPoints(),
                Math.max(0, grant.activatedPoints()-grant.reclaimedAvailable()),
                Math.max(0, grant.outcome().grantedPoints()-grant.activatedPoints()-grant.reclaimedPending()),
                Math.addExact(grant.reclaimedPending(),grant.reclaimedAvailable()),grant.outcome().reason());
    }

    @Override
    public PositionView position(String type, PositionMove.Kind kind, String reference) {
        type(type);
        require(kind != null && nonblank(reference), "POINTS_POSITION_INPUT_INVALID");
        var row = read.position(kind.table(), reference);
        require(row != null && type.equals(row.get("type_code")), "POINTS_POSITION_UNKNOWN");
        return new PositionView(
                type,
                kind,
                reference,
                text(row, "state"),
                num(row, "original_points"),
                num(row, "processed_points"),
                num(row, "version"));
    }

    @Override
    public Snapshot stats(String type, LocalDate day, StatsScope scope, String subject) {
        type(type);
        require(day != null && scope != null, "POINTS_STATS_INPUT_INVALID");
        require(scope == StatsScope.TOTAL || nonblank(subject), "POINTS_STATS_SCOPE_INVALID");
        String key =
                switch (scope) {
                    case TOTAL -> "TOTAL";
                    case STORE -> "STORE:" + PointsKeys.position("S", type, subject);
                    case USER -> "USER:" + PointsKeys.position("U", type, subject);
                };
        return statistics.read(type, day, key);
    }

    @Override
    public PointsStatsService.PeriodSnapshot periodStats(String type, LocalDate from, LocalDate to) {
        type(type);
        return statistics.period(type, from, to);
    }

    @Override
    public OperatorResult rebuildStats(StatsRebuild r, String actor) {
        return journal.apply(
                "STATS_REBUILD",
                r.requestId(),
                r.typeCode(),
                actor,
                r.reason(),
                r,
                () -> {
                    statistics.rebuild(r.typeCode(), r.day());
                    return r.day().toString();
                });
    }

    @Override
    public Report reconciliation(String type, LocalDate day) {
        type(type);
        require(day != null, "POINTS_RECON_DAY_REQUIRED");
        var row = store.stat(type, day, "RECON");
        return row == null ? null : json.readValue(text(row, "payload_json"), Report.class);
    }

    @Override
    public OperatorResult reconcile(ReconRun r, String actor) {
        return journal.apply(
                "RECON_RUN",
                r.requestId(),
                r.typeCode(),
                actor,
                r.reason(),
                r,
                () -> {
                    long at = clock.millis();
                    recon.scan(r.typeCode(), at);
                    // Existing reconciliation snapshots are keyed by their Shanghai accounting
                    // date.
                    return Instant.ofEpochMilli(at)
                            .atZone(ZoneId.of("Asia/Shanghai"))
                            .toLocalDate()
                            .toString();
                });
    }

    private void type(String type) {
        require(nonblank(type) && read.type(type).isPresent(), "POINTS_TYPE_UNKNOWN");
    }

    private void page(String type, long after, int limit) {
        type(type);
        require(after >= 0 && limit >= 1 && limit <= 200, "POINTS_PAGE_INVALID");
    }

    private boolean nonblank(String value) {
        return value != null && !value.isBlank();
    }

    private long num(Map<String, Object> row, String field) {
        return ((Number) row.get(field)).longValue();
    }

    private long time(Map<String, Object> row, String field) {
        Object value = row.get(field);
        if (value instanceof java.util.Date date) return date.getTime();
        if (value instanceof java.time.LocalDateTime local)
            return local.atZone(ZoneId.of("Asia/Shanghai")).toInstant().toEpochMilli();
        throw new IllegalStateException("POINTS_ENTRY_TIME_MISSING");
    }

    private String text(Map<String, Object> row, String field) {
        return (String) row.get(field);
    }

    private void require(boolean value, String code) {
        if (!value) throw new IllegalArgumentException(code);
    }
}
