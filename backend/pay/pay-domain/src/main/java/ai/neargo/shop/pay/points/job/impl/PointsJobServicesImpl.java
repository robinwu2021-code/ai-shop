package ai.neargo.shop.pay.points.job.impl;

import ai.neargo.shop.pay.points.event.PointsAttentionService;
import ai.neargo.shop.pay.points.event.PointsEventWorker;
import ai.neargo.shop.pay.points.job.PointsJobServices;
import ai.neargo.shop.pay.points.job.PointsRecoveryService;
import ai.neargo.shop.pay.points.job.PointsRecoveryService.Operation;
import ai.neargo.shop.pay.points.job.PointsRecoveryService.Work;
import ai.neargo.shop.pay.points.lifecycle.ExpiryReminderService;
import ai.neargo.shop.pay.points.lifecycle.ExpiryService;
import ai.neargo.shop.pay.points.pool.TopupPlanService;
import ai.neargo.shop.pay.points.port.PointsIncomeAccountProvider;
import ai.neargo.shop.pay.points.query.PointsStatsService;
import ai.neargo.shop.pay.points.recon.PointsReconService;
import ai.neargo.shop.pay.points.recon.PointsSourceReconciler;
import ai.neargo.shop.pay.points.store.PointsCursorStore;
import ai.neargo.shop.pay.points.store.PointsOperationsStore;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Set;

/**
 * Separate jobs expose precise progress; a large batch never holds a pay transaction across remote
 * work.
 */
@Service
public class PointsJobServicesImpl implements PointsJobServices {
    private final PointsSourceReconciler source;
    private final PointsEventWorker events;
    private final PointsRecoveryService recovery;
    private final ExpiryReminderService reminders;
    private final ExpiryService expiry;
    private final ObjectProvider<PointsIncomeAccountProvider> income;
    private final PointsStatsService stats;
    private final PointsReconService recon;
    private final TopupPlanService plans;
    private final PointsOperationsStore store;
    private final PointsCursorStore cursors;
    private final PointsAttentionService attention;
    @org.springframework.beans.factory.annotation.Autowired
    private ObjectProvider<ai.neargo.shop.pay.points.statement.impl.StatementConfirmationServiceImpl> statementConfirmation;
    @org.springframework.beans.factory.annotation.Autowired
    private ObjectProvider<ai.neargo.shop.pay.points.store.StatementBuildStore> statementBuildStore;
    @org.springframework.beans.factory.annotation.Autowired
    private ObjectProvider<ai.neargo.shop.pay.points.statement.StoreStatementService> statements;
    private final ObjectMapper json = new ObjectMapper();

    public PointsJobServicesImpl(
            PointsSourceReconciler source,
            PointsEventWorker events,
            PointsRecoveryService recovery,
            ExpiryReminderService reminders,
            ExpiryService expiry,
            ObjectProvider<PointsIncomeAccountProvider> income,
            PointsStatsService stats,
            PointsReconService recon,
            TopupPlanService plans,
            PointsOperationsStore store,
            PointsCursorStore cursors,
            PointsAttentionService attention) {
        this.source = source;
        this.events = events;
        this.recovery = recovery;
        this.reminders = reminders;
        this.expiry = expiry;
        this.income = income;
        this.stats = stats;
        this.recon = recon;
        this.plans = plans;
        this.store = store;
        this.cursors = cursors;
        this.attention = attention;
    }

    @Override
    public Result run(Task task, String type, int limit, long now, LocalDate businessDate) {
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("POINTS_JOB_REQUIRES_NO_TRANSACTION");
        }
        if (task == null
                || limit < 1
                || limit > 200
                || now < 0
                || (task != Task.DISPATCH && (type == null || type.isBlank()))) {
            throw new IllegalArgumentException("POINTS_JOB_INPUT_INVALID");
        }
        LocalDate day =
                businessDate == null
                        ? Instant.ofEpochMilli(now).atZone(ZoneId.of("Asia/Shanghai")).toLocalDate()
                        : businessDate;
        return switch (task) {
            case SOURCE -> {
                var result = source.scan(type, limit);
                yield new Result(
                        result.available(),
                        result.queued(),
                        result.available() ? 0 : 1,
                        store.manualEvents(type));
            }
            case DISPATCH -> {
                var result = events.process(type, limit, now);
                yield new Result(
                        true, result.succeeded(), result.retried(), store.manualEvents(type));
            }
            case RECOVERY ->
                    new Result(
                            true, recovery.discover(type, limit, now), 0, store.manualEvents(type));
            case LIFECYCLE -> lifecycle(type, limit, now);
            case RECON -> {
                var report = recon.scan(type, now);
                yield new Result(
                        report.complete(),
                        1,
                        report.complete() ? 0 : 1,
                        report.differences().size());
            }
            case STATS_PROJECT -> new Result(true, stats.project(type, limit), 0, 0);
            case STATS_REBUILD -> {
                stats.rebuild(type, day);
                yield new Result(true, 1, 0, 0);
            }
            case STATEMENTS -> statementBuilds(type,limit);
            case STATEMENT_CONFIRM -> {
                var service=statementConfirmation==null?null:statementConfirmation.getIfAvailable();
                if(service==null) yield new Result(false,0,1,0);
                var result=service.run(type,limit,now);
                yield new Result(true,result.processed(),result.deferred(),0);
            }
            case PLANS -> new Result(true, plans.tick(type, day, limit), 0, 0);
        };
    }

    private Result statementBuilds(String type,int limit) {
        var builds=statementBuildStore.getIfAvailable();
        var service=statements.getIfAvailable();
        if (builds==null || service==null) return new Result(false,0,1,0);
        String key="PTS_STATEMENT_BUILDS:"+type;
        var cursor=cursors.state(key);
        String after=json.readTree(cursor.payload()).path("afterBuildNo").asText("");
        var page=builds.pending(type,after,limit);
        int visited=0,processed=0,deferred=0;
        long started=System.nanoTime();
        for (var build:page) {
            if (visited>0 && (System.nanoTime()-started)/1000000>=20000) break;
            try {
                service.draftGroups(new ai.neargo.shop.pay.points.statement.StoreStatementService.Period(
                        build.typeCode(),build.storeNo(),build.fromMillis(),build.toMillis()),"SYSTEM:STATEMENT_BUILD");
                processed++;
            } catch (IllegalArgumentException failure) {
                if (!Set.of("POINTS_STATEMENT_SOURCE_NOT_READY","POINTS_STATEMENT_ROUTE_GROUP_LIMIT")
                        .contains(failure.getMessage())) throw failure;
                deferred++;
            }
            after=build.buildNo(); visited++;
        }
        if (visited==page.size() && page.size()<limit) after="";
        cursors.advance(key,type,cursor.watermark(),Math.addExact(cursor.watermark(),1),
                json.writeValueAsString(java.util.Map.of("afterBuildNo",after)));
        return new Result(true,processed,deferred,0);
    }

    private Result lifecycle(String type, int limit, long now) {
        String task = "PTS_LIFECYCLE:" + type;
        var state = cursors.state(task);
        long after = json.readTree(state.payload()).path("afterId").asLong(0);
        var page = store.lifecycleAccounts(type, after, limit);
        int processed = 0, deferred = 0, visited = 0;
        long last = after, started = System.nanoTime();
        var provider = income.getIfAvailable();
        var account = provider == null ? null : provider.accountFor(type);
        for (var row : page) {
            if (visited > 0 && (System.nanoTime() - started) / 1000000 >= 20000) {
                break;
            }
            String user = (String) row.get("holder_no");
            if (reminders.prepare(type, user, now, 7)) {
                processed++;
            }
            if (((Number) row.get("expire_at")).longValue() <= now) {
                if (account == null) {
                    deferred++;
                    attention.raise(type, "EXPIRY_INCOME_UNAVAILABLE", type, 1, 0, now);
                } else {
                    try {
                        var command =
                                expiry.prepare(
                                        new ExpiryService.Request(
                                                type,
                                                user,
                                                ((Number) row.get("expiry_revision")).longValue(),
                                                now,
                                                account));
                        recovery.schedule(new Work(type, Operation.TRANSFER, command.ticketNo()));
                        processed++;
                    } catch (IllegalArgumentException failure) {
                        if (!Set.of(
                                        "POINTS_EXPIRY_NOT_DUE",
                                        "POINTS_EXPIRY_EMPTY",
                                        "POINTS_DISABLED",
                                        "POINTS_FROZEN")
                                .contains(failure.getMessage())) {
                            throw failure;
                        }
                    }
                }
            }
            visited++;
            last = ((Number) row.get("id")).longValue();
        }
        long next = visited == page.size() && page.size() < limit ? 0 : last;
        cursors.advance(
                task,
                type,
                state.watermark(),
                Math.addExact(state.watermark(), 1),
                json.writeValueAsString(new Cursor(next)));
        return new Result(true, processed, deferred, store.manualEvents(type));
    }

    private record Cursor(long afterId) {}
}
