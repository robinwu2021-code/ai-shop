package ai.neargo.shop.pay.points.store.impl;

import ai.neargo.common.data.scope.DataScopeContext;
import ai.neargo.shop.pay.mapper.points.PointsOperationsMapper;
import ai.neargo.shop.pay.points.store.PointsOperationsStore;

import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Repository
public class MybatisPointsOperationsStore implements PointsOperationsStore {
    private final PointsOperationsMapper mapper;

    public MybatisPointsOperationsStore(PointsOperationsMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public List<Map<String, Object>> switches(String type, long after, int limit) {
        require(after >= 0 && limit > 0 && limit <= 200, "POINTS_PAGE_INVALID");
        return DataScopeContext.executeWithoutScope(() -> mapper.switches(type, after, limit));
    }

    @Override
    public List<Map<String, Object>> agreements(String type, String store, long after, int limit) {
        require(after >= 0 && limit > 0 && limit <= 200, "POINTS_PAGE_INVALID");
        return DataScopeContext.executeWithoutScope(
                () -> mapper.agreements(type, store, after, limit));
    }

    @Override
    public List<Map<String, Object>> receivables(String type, String store, long after, int limit) {
        require(
                type != null
                        && !type.isBlank()
                        && store != null
                        && !store.isBlank()
                        && after >= 0
                        && limit > 0
                        && limit <= 200,
                "POINTS_PAGE_INVALID");
        return DataScopeContext.executeWithoutScope(
                () -> mapper.receivables(type, store, after, limit));
    }

    @Override
    public long outstandingReceivable(String type) {
        return DataScopeContext.executeWithoutScope(() -> mapper.outstandingReceivable(type));
    }

    @Override
    public long manualEvents(String type) {
        return DataScopeContext.executeWithoutScope(() -> mapper.manualEvents(type));
    }

    @Override
    public Map<String, Object> plan(String plan, String period) {
        return DataScopeContext.executeWithoutScope(() -> mapper.plan(plan, period));
    }

    @Override
    public Map<String, Object> lockPlan(String plan, String period) {
        return DataScopeContext.executeWithoutScope(() -> mapper.lockPlan(plan, period));
    }

    @Override
    public List<Map<String, Object>> plans(
            String type, long after, int limit, boolean configurations) {
        require(after >= 0 && limit > 0 && limit <= 200, "POINTS_PAGE_INVALID");
        return DataScopeContext.executeWithoutScope(
                () -> mapper.plans(type, after, limit, configurations));
    }

    @Override
    public void savePlan(
            String type, String plan, String period, String state, String body, long version) {
        int changed =
                DataScopeContext.executeWithoutScope(
                        () ->
                                version < 0
                                        ? mapper.insertPlan(type, plan, period, state, body)
                                        : mapper.updatePlan(
                                                type, plan, period, state, body, version));
        require(changed == 1, "POINTS_PLAN_VERSION_CONFLICT");
    }

    @Override
    public List<Map<String, Object>> pending(
            String type, Kind kind, List<String> states, long after, int limit) {
        require(
                (kind == Kind.PAYOUT || kind == Kind.REFUND || kind == Kind.TRANSFER)
                        && after >= 0
                        && !states.isEmpty()
                        && limit > 0
                        && limit <= 200,
                "POINTS_PAGE_INVALID");
        return DataScopeContext.executeWithoutScope(
                () -> mapper.pending(kind.table(), type, states, after, limit));
    }

    @Override
    public List<Map<String, Object>> lifecycleAccounts(String type, long after, int limit) {
        require(after >= 0 && limit > 0 && limit <= 200, "POINTS_PAGE_INVALID");
        return DataScopeContext.executeWithoutScope(
                () -> mapper.lifecycleAccounts(type, after, limit));
    }

    @Override
    public List<Map<String, Object>> page(String type, Kind kind, long after, int limit) {
        require(kind != null && after >= 0 && limit > 0 && limit <= 200, "POINTS_PAGE_INVALID");
        return DataScopeContext.executeWithoutScope(
                () -> mapper.page(kind.table(), type, after, limit));
    }

    @Override
    public List<Map<String, Object>> vouchers(String type, LocalDate day, long after, int limit) {
        require(day != null && after >= 0 && limit > 0 && limit <= 200, "POINTS_PAGE_INVALID");
        return DataScopeContext.executeWithoutScope(
                () ->
                        mapper.vouchers(
                                type,
                                day.atStartOfDay(),
                                day.plusDays(1).atStartOfDay(),
                                after,
                                limit));
    }

    @Override
    public List<Map<String, Object>> dayRows(
            String type, Kind kind, LocalDate day, long after, int limit) {
        require(
                (kind == Kind.ENTRY || kind == Kind.FLOW)
                        && day != null
                        && after >= 0
                        && limit > 0
                        && limit <= 200,
                "POINTS_PAGE_INVALID");
        return DataScopeContext.executeWithoutScope(
                () ->
                        mapper.dayRows(
                                kind.table(),
                                type,
                                day.atStartOfDay(),
                                day.plusDays(1).atStartOfDay(),
                                after,
                                limit));
    }

    @Override
    public List<Map<String, Object>> postingEvents(String type, int limit) {
        require(limit > 0 && limit <= 200, "POINTS_PAGE_INVALID");
        return DataScopeContext.executeWithoutScope(() -> mapper.postingEvents(type, limit));
    }

    @Override
    public void projected(long id, long version) {
        require(
                DataScopeContext.executeWithoutScope(() -> mapper.projected(id, version)) == 1,
                "POINTS_EVENT_LEASE_LOST");
    }

    @Override
    public long evidenceTotal(String type) {
        return DataScopeContext.executeWithoutScope(() -> mapper.evidenceTotal(type));
    }

    @Override
    public Map<String, Object> stat(String type, LocalDate day, String scope) {
        return DataScopeContext.executeWithoutScope(() -> mapper.stat(type, day.toString(), scope));
    }

    @Override
    public void saveStat(String type, LocalDate day, String scope, String state, String payload) {
        DataScopeContext.executeWithoutScope(
                () -> mapper.saveStat(type, day.toString(), scope, state, payload));
    }

    @Override
    public void clearStats(String type, LocalDate day) {
        DataScopeContext.executeWithoutScope(() -> mapper.clearStats(type, day.toString()));
    }

    @Override
    public List<String> heldSubOrders(long olderThan, int limit) {
        require(limit > 0 && limit <= 200, "POINTS_PAGE_INVALID");
        var cutoff =
                Instant.ofEpochMilli(olderThan)
                        .atZone(ZoneId.of("Asia/Shanghai"))
                        .toLocalDateTime();
        return DataScopeContext.executeWithoutScope(() -> mapper.heldSubOrders(cutoff, limit));
    }

    @Override
    public Set<String> grantedSubOrders(Collection<String> subs) {
        if (subs.isEmpty()) {
            return Set.of();
        }
        require(subs.size() <= 200, "POINTS_PAGE_INVALID");
        return DataScopeContext.executeWithoutScope(
                () -> Set.copyOf(mapper.grantedSubOrders(subs)));
    }

    private void require(boolean condition, String reason) {
        if (!condition) {
            throw new IllegalArgumentException(reason);
        }
    }
}
