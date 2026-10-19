package ai.neargo.shop.pay.points.admin.impl;

import ai.neargo.shop.pay.points.admin.PointsOperatorJournal;
import ai.neargo.shop.pay.points.admin.PointsOperatorJournal.OperatorResult;
import ai.neargo.shop.pay.points.admin.PointsWorkbenchService;
import ai.neargo.shop.pay.points.event.PointsEventQueue;
import ai.neargo.shop.pay.points.job.PointsRecoveryService;
import ai.neargo.shop.pay.points.job.PointsRecoveryService.Operation;
import ai.neargo.shop.pay.points.model.PointsKeys;
import ai.neargo.shop.pay.points.model.PositionMove;
import ai.neargo.shop.pay.points.payout.PayoutSnapshot;
import ai.neargo.shop.pay.points.payout.StatementPayoutService;
import ai.neargo.shop.pay.points.refund.PointsReceivableRecoveryService;
import ai.neargo.shop.pay.points.refund.PointsRefundCollectionService;
import ai.neargo.shop.pay.points.refund.impl.PointsReceivableRecoveryServiceImpl.RecoverySnapshot;
import ai.neargo.shop.pay.points.refund.impl.PointsRefundPreparationServiceImpl.RefundSnapshot;
import ai.neargo.shop.pay.points.statement.StoreStatementService;
import ai.neargo.shop.pay.points.statement.StoreStatementService.Snapshot;
import ai.neargo.shop.pay.points.store.PointsConfigurationStore;
import ai.neargo.shop.pay.points.store.PointsOperationsStore;
import ai.neargo.shop.pay.points.store.PointsOperationsStore.Kind;
import ai.neargo.shop.pay.points.store.PointsReadStore;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import tools.jackson.databind.ObjectMapper;

import java.time.Clock;
import java.util.Map;

@Service
public class PointsWorkbenchServiceImpl implements PointsWorkbenchService {
    private final PointsOperatorJournal journal;
    private final PointsReadStore read;
    private final PointsOperationsStore operations;
    private final PointsConfigurationStore configuration;
    private final StoreStatementService statements;
    private final StatementPayoutService payouts;
    private final PointsReceivableRecoveryService receivables;
    private final PointsRefundCollectionService collections;
    private final PointsRecoveryService recovery;
    private final PointsEventQueue queue;
    private final Clock clock;
    private final ObjectMapper json = new ObjectMapper();
    @org.springframework.beans.factory.annotation.Autowired
    private ObjectProvider<ai.neargo.shop.pay.points.statement.StatementBuildService> statementBuilders;

    public PointsWorkbenchServiceImpl(
            PointsOperatorJournal journal,
            PointsReadStore read,
            PointsOperationsStore operations,
            PointsConfigurationStore configuration,
            StoreStatementService statements,
            StatementPayoutService payouts,
            PointsReceivableRecoveryService receivables,
            PointsRefundCollectionService collections,
            PointsRecoveryService recovery,
            PointsEventQueue queue,
            ObjectProvider<Clock> clocks) {
        this.journal = journal;
        this.read = read;
        this.operations = operations;
        this.configuration = configuration;
        this.statements = statements;
        this.payouts = payouts;
        this.receivables = receivables;
        this.collections = collections;
        this.recovery = recovery;
        this.queue = queue;
        this.clock = clocks.getIfAvailable(Clock::systemUTC);
    }

    @Override
    public StatementView statement(Reference r) {
        requiredStore(r.storeNo());
        var head = read.position(PositionMove.Kind.STATEMENT.table(), r.reference());
        require(head != null && r.typeCode().equals(head.get("type_code")), "POINTS_STATEMENT_UNKNOWN");
        var saved = json.readValue((String) head.get("payload_json"), Snapshot.class);
        require(r.storeNo().equals(saved.period().storeNo()), "POINTS_STORE_MISMATCH");
        return new StatementView(num(head, "id"), saved, num(head, "version"));
    }

    @Override
    public StatementLinePage statementLines(StatementLines r) {
        require(r.after() >= 0 && r.limit() > 0 && r.limit() <= 200 && r.revision() > 0,
                "POINTS_PAGE_INVALID");
        var saved = statement(new Reference(r.typeCode(), r.storeNo(), r.statementNo())).snapshot();
        var selected = saved.revisions().stream().filter(x -> x.number() == r.revision()).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("POINTS_STATEMENT_REVISION_CONFLICT"));
        if (saved.sourceBuildNo() != null) {
            var page = statementBuilders.getObject().lines(r.statementNo(), r.revision(), r.after(), r.limit());
            return new StatementLinePage(page.items().stream().map(x -> sourceLine(x.positionNo(), x.points(), r))
                    .toList(), page.nextAfter(), page.hasMore());
        }
        var lines = selected.lines().stream().skip(r.after()).limit((long) r.limit() + 1).toList();
        var rows = lines.stream().limit(r.limit()).map(x -> sourceLine(x.positionNo(), x.points(), r)).toList();
        return new StatementLinePage(rows, r.after() + rows.size(), lines.size() > r.limit());
    }

    private StatementLine sourceLine(String no, long points, StatementLines r) {
        var source = read.position(PositionMove.Kind.REDEEM.table(), no);
        require(source != null && r.typeCode().equals(source.get("type_code"))
                && r.statementNo().equals(source.get("statement_no")), "POINTS_STATEMENT_BINDING_CONFLICT");
        var saved = json.readValue((String) source.get("payload_json"),
                ai.neargo.shop.pay.points.redeem.impl.RedeemServiceImpl.RedeemSnapshot.class);
        require(r.storeNo().equals(saved.share().storeNo()), "POINTS_STORE_MISMATCH");
        return new StatementLine(saved.orderNo(), saved.subOrderNo(), points);
    }

    @Override
    public StatementPage statements(String type, String store, long after, int limit) {
        page(type, after, limit);
        requiredStore(store);
        var rows =
                operations.statements(type, store, after, limit).stream()
                        .map(
                                r ->
                                        new StatementView(
                                                num(r, "id"),
                                                json.readValue(
                                                        (String) r.get("payload_json"),
                                                        Snapshot.class),
                                                num(r, "version")))
                        .toList();
        return new StatementPage(
                rows, rows.isEmpty() ? after : rows.getLast().id(), rows.size() == limit);
    }

    @Override
    public OperatorResult draftStatements(StatementDraft r, String actor) {
        requiredStore(r.storeNo());
        return journal.apply(
                "STATEMENT_DRAFT",
                r.requestId(),
                r.typeCode(),
                actor,
                r.reason(),
                r,
                () ->
                        String.join(
                                ",",
                                statements
                                        .draftGroups(
                                                new StoreStatementService.Period(
                                                        r.typeCode(),
                                                        r.storeNo(),
                                                        r.fromMillis(),
                                                        r.toMillis()),
                                                actor)
                                        .stream()
                                        .map(Snapshot::statementNo)
                                        .toList()));
    }

    @Override
    public OperatorResult changeStatement(StatementChange r, String actor) {
        return journal.apply(
                "STATEMENT_CHANGE",
                r.requestId(),
                r.typeCode(),
                actor,
                r.reason(),
                r,
                () -> {
                    var head = configuration.lockStatement(r.statementNo());
                    require(
                            head != null && r.typeCode().equals(head.get("type_code")),
                            "POINTS_STATEMENT_UNKNOWN");
                    var snapshot =
                            json.readValue((String) head.get("payload_json"), Snapshot.class);
                    require(
                            r.storeNo() != null && r.storeNo().equals(snapshot.period().storeNo()),
                            "POINTS_STORE_MISMATCH");
                    require(
                            snapshot.current().number() == r.revision(),
                            "POINTS_STATEMENT_REVISION_CONFLICT");
                    require(r.action() != null, "POINTS_STATEMENT_ACTION_REQUIRED");
                    switch (r.action()) {
                        case CONFIRM -> statements.confirm(r.statementNo(), r.revision(), actor);
                        case DISPUTE ->
                                statements.dispute(
                                        r.statementNo(), r.revision(), actor, r.reason());
                        case REDRAFT -> statements.redraft(r.statementNo(), r.revision(), actor);
                        case PREPARE_PAYOUT -> {
                            return payouts.prepare(
                                            PointsKeys.position("OP", r.typeCode(), r.requestId()),
                                            r.statementNo(),
                                            r.revision(),
                                            actor)
                                    .ticketNo();
                        }
                    }
                    return r.statementNo();
                });
    }

    @Override
    public RefundView refund(Reference r) {
        var head = scoped(r.typeCode(), r.storeNo(), Operation.REFUND_COLLECTION, r.reference());
        var saved = json.readValue((String) head.get("payload_json"), RefundSnapshot.class);
        return new RefundView(
                r.typeCode(),
                r.storeNo(),
                saved.command().afterSaleNo(),
                saved.command().subOrderNo(),
                (String) head.get("state"),
                num(head, "version"),
                saved.preparation());
    }

    @Override
    public OperatorResult reviseRefund(Revision r, String actor) {
        return journal.apply(
                "REFUND_REVISE",
                r.requestId(),
                r.typeCode(),
                actor,
                r.reason(),
                r,
                () -> {
                    var head =
                            scoped(
                                    r.typeCode(),
                                    r.storeNo(),
                                    Operation.REFUND_COLLECTION,
                                    r.reference());
                    require(
                            num(head, "version") == r.expectedVersion(),
                            "POINTS_REFUND_REVISION_CONFLICT");
                    collections.reviseFailed(r.reference());
                    return r.reference();
                });
    }

    @Override
    public OperatorResult prepareReceivable(ReceivableRecovery r, String actor) {
        return journal.apply(
                "RECEIVABLE_PREPARE",
                r.requestId(),
                r.typeCode(),
                actor,
                r.reason(),
                r,
                () -> {
                    var head = configuration.lockReceivable(r.receivableNo());
                    require(
                            head != null && r.typeCode().equals(head.get("type_code")),
                            "POINTS_RECEIVABLE_UNKNOWN");
                    require(
                            r.storeNo() != null && r.storeNo().equals(head.get("store_no")),
                            "POINTS_STORE_MISMATCH");
                    require(
                            num(head, "version") == r.expectedVersion(),
                            "POINTS_RECEIVABLE_REVISION_CONFLICT");
                    return receivables
                            .prepare(
                                    PointsKeys.position("OP", r.typeCode(), r.requestId()),
                                    r.receivableNo(),
                                    r.amountMinor())
                            .ticketNo();
                });
    }

    @Override
    public FundsView funds(FundsReference r) {
        var row = scoped(r.typeCode(), r.storeNo(), r.operation(), r.reference());
        long gross = 0, offset = 0, cash = 0;
        if (r.operation() == Operation.PAYOUT) {
            var s = json.readValue((String) row.get("payload_json"), PayoutSnapshot.class);
            gross = s.ticket().gross();
            offset = s.ticket().offset();
            cash = s.ticket().cash();
        } else if (r.operation() == Operation.TRANSFER) {
            cash =
                    json.readValue((String) row.get("payload_json"), RecoverySnapshot.class)
                            .fundsCommand()
                            .amountMinor();
        } else {
            var s = json.readValue((String) row.get("payload_json"), RefundSnapshot.class);
            var command =
                    r.operation() == Operation.REFUND_COLLECTION
                            ? s.collectionCommand()
                            : s.compensation() == null ? null : s.compensation().command();
            cash = command == null ? 0 : command.amountMinor();
        }
        return new FundsView(
                r.typeCode(),
                r.storeNo(),
                r.operation(),
                r.reference(),
                (String) row.get("state"),
                num(row, "version"),
                gross,
                offset,
                cash);
    }

    @Override
    public Execution executeRecovery(Recovery r, String actor) {
        journal.apply(
                "FUNDS_RECOVERY",
                r.requestId(),
                r.typeCode(),
                actor,
                r.reason(),
                r,
                () -> {
                    var row = scoped(r.typeCode(), r.storeNo(), r.operation(), r.reference());
                    require(
                            num(row, "version") == r.expectedVersion(),
                            "POINTS_FUNDS_REVISION_CONFLICT");
                    return r.reference();
                });
        return new Execution(
                r.reference(),
                recovery.execute(
                        new PointsRecoveryService.Work(
                                r.typeCode(), r.operation(), r.reference())));
    }

    @Override
    public EventPage events(String type, long after, int limit) {
        page(type, after, limit);
        var rows =
                operations.page(type, Kind.OUTBOX, after, limit).stream()
                        .map(
                                r ->
                                        new EventView(
                                                num(r, "id"),
                                                (String) r.get("event_no"),
                                                json.readTree((String) r.get("payload_json"))
                                                        .path("kind")
                                                        .asText(),
                                                (String) r.get("state"),
                                                num(r, "retry_count"),
                                                num(r, "next_attempt"),
                                                num(r, "version")))
                        .toList();
        return new EventPage(
                rows, rows.isEmpty() ? after : rows.getLast().id(), rows.size() == limit);
    }

    @Override
    public OperatorResult retryEvent(EventRetry r, String actor) {
        return journal.apply(
                "EVENT_RETRY",
                r.requestId(),
                r.typeCode(),
                actor,
                r.reason(),
                r,
                () -> {
                    var head = operations.lockEvent(r.eventId());
                    require(
                            head != null && r.typeCode().equals(head.get("type_code")),
                            "POINTS_EVENT_UNKNOWN");
                    require(
                            num(head, "version") == r.expectedVersion(),
                            "POINTS_EVENT_REVISION_CONFLICT");
                    queue.retry(r.eventId(), actor, r.reason(), clock.millis());
                    return (String) head.get("event_no");
                });
    }

    private Map<String, Object> scoped(
            String type, String store, Operation operation, String reference) {
        requiredStore(store);
        require(operation != null, "POINTS_RECOVERY_OPERATION_REQUIRED");
        String table =
                switch (operation) {
                    case PAYOUT -> PositionMove.Kind.PAYOUT.table();
                    case TRANSFER -> PositionMove.Kind.TRANSFER.table();
                    default -> PositionMove.Kind.REFUND.table();
                };
        var head = read.position(table, reference);
        require(
                head != null && type != null && type.equals(head.get("type_code")),
                "POINTS_REFERENCE_UNKNOWN");
        var node = json.readTree((String) head.get("payload_json"));
        String actual =
                switch (operation) {
                    case PAYOUT -> node.path("command").path("context").path("storeNo").asText();
                    case TRANSFER -> {
                        require(
                                node.hasNonNull("receivableNo"),
                                "POINTS_RECOVERY_TRANSFER_INVALID");
                        yield node.path("fundsCommand").path("context").path("storeNo").asText();
                    }
                    default -> node.path("command").path("payment").path("storeNo").asText();
                };
        require(store.equals(actual), "POINTS_STORE_MISMATCH");
        return head;
    }

    private void page(String type, long after, int limit) {
        require(
                type != null
                        && read.type(type).isPresent()
                        && after >= 0
                        && limit >= 1
                        && limit <= 200,
                "POINTS_PAGE_INVALID");
    }

    private void requiredStore(String store) {
        require(
                store != null && !store.isBlank() && !"PLATFORM".equals(store),
                "POINTS_STORE_REQUIRED");
    }

    private long num(Map<String, Object> r, String field) {
        return ((Number) r.get(field)).longValue();
    }

    private void require(boolean value, String code) {
        if (!value) throw new IllegalArgumentException(code);
    }
}
