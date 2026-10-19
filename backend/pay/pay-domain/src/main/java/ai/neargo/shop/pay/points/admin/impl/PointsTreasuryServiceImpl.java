package ai.neargo.shop.pay.points.admin.impl;

import ai.neargo.shop.pay.market.MarketService;
import ai.neargo.shop.pay.points.admin.PointsOperatorJournal;
import ai.neargo.shop.pay.points.admin.PointsOperatorJournal.OperatorResult;
import ai.neargo.shop.pay.points.admin.PointsTreasuryService;
import ai.neargo.shop.pay.points.model.PointsKeys;
import ai.neargo.shop.pay.points.model.PoolRequest;
import ai.neargo.shop.pay.points.model.PositionMove;
import ai.neargo.shop.pay.points.pool.PoolRequestService;
import ai.neargo.shop.pay.points.pool.PoolWithdrawalExecutionService;
import ai.neargo.shop.pay.points.pool.TopupPlanService;
import ai.neargo.shop.pay.points.pool.impl.PoolRequestServiceImpl.Envelope;
import ai.neargo.shop.pay.points.store.PointsConfigurationStore;
import ai.neargo.shop.pay.points.store.PointsOperationsStore;
import ai.neargo.shop.pay.points.store.PointsOperationsStore.Kind;
import ai.neargo.shop.pay.points.store.PointsReadStore;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import tools.jackson.databind.ObjectMapper;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Map;

/** Operator adapters retain the kernel's receipt verification and maker/checker state machines. */
@Service
public class PointsTreasuryServiceImpl implements PointsTreasuryService {
    private final PointsOperatorJournal journal;
    private final PoolRequestService requests;
    private final PointsConfigurationStore configuration;
    private final PointsOperationsStore operations;
    private final PointsReadStore read;
    private final TopupPlanService plans;
    private final PoolWithdrawalExecutionService withdrawals;
    private final Clock clock;
    private final MarketService markets;
    private final ObjectMapper json = new ObjectMapper();

    public PointsTreasuryServiceImpl(
            PointsOperatorJournal journal,
            PoolRequestService requests,
            PointsConfigurationStore configuration,
            PointsOperationsStore operations,
            PointsReadStore read,
            TopupPlanService plans,
            PoolWithdrawalExecutionService withdrawals,
            ObjectProvider<Clock> clocks,
            MarketService markets) {
        this.journal = journal;
        this.requests = requests;
        this.configuration = configuration;
        this.operations = operations;
        this.read = read;
        this.plans = plans;
        this.withdrawals = withdrawals;
        this.clock = clocks.getIfAvailable(Clock::systemUTC);
        this.markets = markets;
    }

    @Override
    public OperatorResult submit(Submission r, String actor) {
        require(
                r != null
                        && r.requestNo() != null
                        && !r.requestNo().isBlank()
                        && r.requestNo().length() <= 40,
                "POINTS_REQUEST_INVALID");
        return journal.apply(
                "POOL_SUBMIT",
                r.requestId(),
                r.typeCode(),
                actor,
                r.reason(),
                r,
                () -> {
                    requests.submit(
                            new PoolRequest(
                                    r.requestNo(),
                                    r.typeCode(),
                                    r.action(),
                                    r.amountMinor(),
                                    actor,
                                    r.evidenceNo(),
                                    r.fundsContext()));
                    return r.requestNo();
                });
    }

    @Override
    public OperatorResult review(Review r, String actor) {
        return journal.apply(
                "POOL_REVIEW",
                r.requestId(),
                r.typeCode(),
                actor,
                r.reason(),
                r,
                () -> {
                    var row = configuration.lockRequest(r.requestNo());
                    require(
                            row != null && r.typeCode().equals(row.get("type_code")),
                            "POINTS_REQUEST_UNKNOWN");
                    require(!actor.equals(row.get("submitted_by")), "POINTS_SELF_APPROVE");
                    require("SUBMITTED".equals(row.get("state")), "POINTS_REQUEST_STATE_CONFLICT");
                    require(
                            num(row, "version") == r.expectedVersion(),
                            "POINTS_REQUEST_REVISION_CONFLICT");
                    if (r.approve()) requests.approve(r.requestNo(), actor);
                    else configuration.rejectRequest(r.requestNo(), r.expectedVersion(), actor);
                    return r.requestNo();
                });
    }

    @Override
    public RequestPage requests(String type, long after, int limit) {
        page(type, after, limit);
        var rows =
                operations.page(type, Kind.REQUEST, after, limit).stream()
                        .map(
                                r -> {
                                    var request =
                                            json.readValue(
                                                            (String) r.get("payload_json"),
                                                            Envelope.class)
                                                    .request();
                                    boolean withdrawal =
                                            request.action() == PoolRequest.Action.WITHDRAW_U
                                                    || request.action()
                                                            == PoolRequest.Action.WITHDRAW_B;
                                    return new RequestView(
                                            num(r, "id"),
                                            request.requestNo(),
                                            type,
                                            request.action(),
                                            request.amountMinor(),
                                            (String) r.get("state"),
                                            request.submittedBy(),
                                            (String) r.get("approved_by"),
                                            request.evidenceNo() != null
                                                    && !request.evidenceNo().isBlank(),
                                            withdrawal
                                                    ? PointsKeys.position(
                                                            "W", type, request.requestNo())
                                                    : null,
                                            num(r, "version"));
                                })
                        .toList();
        return new RequestPage(
                rows, rows.isEmpty() ? after : rows.getLast().id(), rows.size() == limit);
    }

    @Override
    public OperatorResult savePlan(PlanChange r, String actor) {
        require(r != null && r.plan() != null, "POINTS_PLAN_INPUT_INVALID");
        return journal.apply(
                "PLAN_SAVE",
                r.requestId(),
                r.plan().typeCode(),
                actor,
                r.reason(),
                r,
                () -> {
                    var row = operations.lockPlan(r.plan().planNo(), "CONFIG");
                    require(
                            (row == null ? -1 : num(row, "version")) == r.expectedVersion(),
                            "POINTS_PLAN_REVISION_CONFLICT");
                    plans.save(
                            PointsKeys.position("OP", r.plan().typeCode(), r.requestId()),
                            r.plan(),
                            actor,
                            r.reason());
                    return r.plan().planNo();
                });
    }

    @Override
    public OperatorResult generatePeriods(GeneratePeriods r, String actor) {
        return journal.apply(
                "PLAN_GENERATE",
                r.requestId(),
                r.typeCode(),
                actor,
                r.reason(),
                r,
                () ->
                        Integer.toString(
                                plans.tick(
                                        r.typeCode(),
                                        LocalDate.ofInstant(
                                                clock.instant(),
                                                ZoneId.of(
                                                        markets.find(
                                                                        read.type(r.typeCode())
                                                                                .orElseThrow()
                                                                                .market())
                                                                .orElseThrow()
                                                                .timeZone())),
                                        r.limit())));
    }

    @Override
    public OperatorResult declareReceipt(PlanReceipt r, String actor) {
        return journal.apply(
                "PLAN_RECEIPT",
                r.requestId(),
                r.typeCode(),
                actor,
                r.reason(),
                r,
                () -> {
                    period(r.typeCode(), r.planNo(), r.period(), r.expectedVersion());
                    return plans.submitReceipt(
                            r.planNo(), r.period(), r.evidenceNo(), actor, r.reason());
                });
    }

    @Override
    public OperatorResult cancelPeriod(PeriodCancellation r, String actor) {
        return journal.apply(
                "PLAN_CANCEL",
                r.requestId(),
                r.typeCode(),
                actor,
                r.reason(),
                r,
                () -> {
                    period(r.typeCode(), r.planNo(), r.period(), r.expectedVersion());
                    plans.cancelPeriod(r.planNo(), r.period(), actor, r.reason());
                    return r.planNo() + ":" + r.period();
                });
    }

    @Override
    public PlanPage plans(String type, long after, int limit) {
        page(type, after, limit);
        var rows =
                operations.page(type, Kind.PLAN, after, limit).stream()
                        .map(
                                r -> {
                                    boolean config = "CONFIG".equals(r.get("period_key"));
                                    var schedule =
                                            config
                                                    ? json.readValue(
                                                            (String) r.get("payload_json"),
                                                            TopupPlanService.Schedule.class)
                                                    : null;
                                    var period =
                                            config
                                                    ? null
                                                    : json.readValue(
                                                            (String) r.get("payload_json"),
                                                            TopupPlanService.Period.class);
                                    var p = config ? schedule.plan() : period.plan();
                                    return new PlanView(
                                            num(r, "id"),
                                            p.planNo(),
                                            (String) r.get("period_key"),
                                            type,
                                            p.action(),
                                            p.amountMinor(),
                                            p.anchor(),
                                            p.everyDays(),
                                            p.enabled(),
                                            config ? schedule.nextPeriod() : null,
                                            (String) r.get("state"),
                                            !config && period.request() != null
                                                    ? period.request().requestNo()
                                                    : null,
                                            num(r, "version"));
                                })
                        .toList();
        return new PlanPage(
                rows, rows.isEmpty() ? after : rows.getLast().id(), rows.size() == limit);
    }

    @Override
    public Execution executeWithdrawal(Withdrawal r, String actor) {
        // Journal commits first. The execution service forbids holding a transaction over gateway
        // I/O.
        journal.apply(
                "WITHDRAW_EXECUTE",
                r.requestId(),
                r.typeCode(),
                actor,
                r.reason(),
                r,
                () -> {
                    var row = read.position(PositionMove.Kind.TRANSFER.table(), r.ticketNo());
                    require(
                            row != null && r.typeCode().equals(row.get("type_code")),
                            "POINTS_WITHDRAW_UNKNOWN");
                    return r.ticketNo();
                });
        var result = withdrawals.execute(r.ticketNo());
        return new Execution(
                result.ticketNo(), result.status(), result.confirmedMinor(), result.replayed());
    }

    private void period(String type, String plan, LocalDate day, long version) {
        require(day != null, "POINTS_PLAN_PERIOD_REQUIRED");
        var row = operations.lockPlan(plan, day.toString());
        require(row != null && type.equals(row.get("type_code")), "POINTS_PLAN_PERIOD_UNKNOWN");
        require(num(row, "version") == version, "POINTS_PLAN_REVISION_CONFLICT");
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

    private long num(Map<String, Object> r, String key) {
        return ((Number) r.get(key)).longValue();
    }

    private void require(boolean value, String code) {
        if (!value) throw new IllegalArgumentException(code);
    }
}
