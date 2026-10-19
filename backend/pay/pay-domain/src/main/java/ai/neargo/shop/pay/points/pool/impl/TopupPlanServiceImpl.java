package ai.neargo.shop.pay.points.pool.impl;

import ai.neargo.shop.pay.points.model.PointType;
import ai.neargo.shop.pay.points.model.PointsKeys;
import ai.neargo.shop.pay.points.model.PoolRequest;
import ai.neargo.shop.pay.points.model.PoolRequest.Action;
import ai.neargo.shop.pay.points.policy.PointsDigests;
import ai.neargo.shop.pay.points.pool.TopupPlanService;
import ai.neargo.shop.pay.points.pool.impl.PoolRequestServiceImpl.Envelope;
import ai.neargo.shop.pay.points.store.PointsConfigurationStore;
import ai.neargo.shop.pay.points.store.PointsCursorStore;
import ai.neargo.shop.pay.points.store.PointsFactStore;
import ai.neargo.shop.pay.points.store.PointsOperationsStore;
import ai.neargo.shop.pay.points.store.PointsReadStore;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import tools.jackson.databind.ObjectMapper;

import java.time.LocalDate;
import java.util.Map;
import java.util.Set;

/**
 * TOPUP periods wait for a declared receipt; the checker must still verify its actual funding
 * evidence.
 */
@Service
@Transactional("payTxManager")
public class TopupPlanServiceImpl implements TopupPlanService {
    private final PointsConfigurationStore config;
    private final PointsOperationsStore store;
    private final PointsReadStore read;
    private final PointsFactStore facts;
    private final PointsCursorStore cursors;
    private final ObjectMapper json = new ObjectMapper();

    public TopupPlanServiceImpl(
            PointsConfigurationStore config,
            PointsOperationsStore store,
            PointsReadStore read,
            PointsFactStore facts,
            PointsCursorStore cursors) {
        this.config = config;
        this.store = store;
        this.read = read;
        this.facts = facts;
        this.cursors = cursors;
    }

    @Override
    public void save(String event, Plan plan, String actor, String reason) {
        audit(actor, reason);
        require(
                event != null
                        && !event.isBlank()
                        && plan != null
                        && plan.planNo() != null
                        && !plan.planNo().isBlank()
                        && plan.planNo().length() <= 40
                        && plan.anchor() != null
                        && plan.everyDays() > 0
                        && plan.everyDays() <= 365
                        && plan.amountMinor() > 0
                        && plan.action() != null
                        && Set.of(Action.TOPUP_U, Action.TOPUP_B, Action.TRANSFER_U_TO_B)
                                .contains(plan.action()),
                "POINTS_PLAN_INPUT_INVALID");
        config.lockTypeAndPool(plan.typeCode());
        var type = read.type(plan.typeCode()).orElseThrow();
        require(type.status() != PointType.Status.RETIRED, "POINTS_TYPE_RETIRED");
        require(
                plan.action() == Action.TOPUP_B
                        || plan.amountMinor() % type.rate().minorUnit() == 0,
                "POINTS_AMOUNT_NOT_ALIGNED");
        String body = json.writeValueAsString(new SaveIntent(event, plan, actor, reason));
        var prior = facts.lockInbox(event);
        if (prior != null) {
            require(body.equals(prior.get("payload_json")), "POINTS_PLAN_EVENT_CONFLICT");
            return;
        }
        var row = store.lockPlan(plan.planNo(), "CONFIG");
        LocalDate next = plan.anchor();
        if (row != null) {
            var old = json.readValue(text(row, "payload_json"), Schedule.class);
            require(
                    old.plan().typeCode().equals(plan.typeCode())
                            && old.plan().action() == plan.action()
                            && old.plan().anchor().equals(plan.anchor())
                            && old.plan().everyDays() == plan.everyDays(),
                    "POINTS_PLAN_CADENCE_IMMUTABLE");
            next = old.nextPeriod();
        }
        store.savePlan(
                plan.typeCode(),
                plan.planNo(),
                "CONFIG",
                plan.enabled() ? "ACTIVE" : "PAUSED",
                json.writeValueAsString(new Schedule(plan, next, actor)),
                row == null ? -1 : num(row, "version"));
        facts.recordEvent(plan.typeCode(), event, PointsDigests.sha256(body), body, "PLAN_CHANGED");
    }

    @Override
    public int tick(String type, LocalDate asOf, int limit) {
        require(asOf != null && limit > 0 && limit <= 200, "POINTS_BATCH_INVALID");
        config.lockTypeAndPool(type);
        String task = PointsKeys.position("PLAN_SCAN", type, type);
        var cursor = cursors.state(task);
        var progress = json.readTree(cursor.payload());
        long submittedAfter = settleSubmitted(type, (progress.has("submittedAfter") ? progress.get("submittedAfter").longValue() : 0), limit);
        long after = (progress.has("configAfter") ? progress.get("configAfter").longValue() : 0);
        var page = store.plans(type, after, limit, true);
        if (page.isEmpty() && after > 0) {
            after = 0;
            page = store.plans(type, after, limit, true);
        }
        int created = 0, attempts = 0;
        for (var candidate : page) {
            var row = store.lockPlan(text(candidate, "plan_no"), "CONFIG");
            after = num(candidate, "id");
            if (!"ACTIVE".equals(row.get("state"))) {
                continue;
            }
            var schedule = json.readValue(text(row, "payload_json"), Schedule.class);
            LocalDate next = schedule.nextPeriod();
            // Attempt budget also bounds recovery over periods already materialized before nextPeriod.
            while (!next.isAfter(asOf) && attempts < limit) {
                attempts++;
                String key = next.toString();
                if (store.lockPlan(schedule.plan().planNo(), key) == null) {
                    PoolRequest request = null;
                    if (schedule.plan().action() == Action.TRANSFER_U_TO_B) {
                        require(schedule.maker() != null && !schedule.maker().isBlank(),
                                "POINTS_PLAN_MAKER_REQUIRED");
                        request = request(schedule.plan(), next, null, schedule.maker());
                        config.submit(request, json.writeValueAsString(new Envelope(request, null)));
                    }
                    store.savePlan(type, schedule.plan().planNo(), key,
                            request == null ? "AWAITING_RECEIPT" : "SUBMITTED",
                            json.writeValueAsString(new Period(schedule.plan(), next, request, null)), -1);
                    created++;
                }
                next = next.plusDays(schedule.plan().everyDays());
            }
            if (!next.equals(schedule.nextPeriod())) {
                store.savePlan(type, schedule.plan().planNo(), "CONFIG", "ACTIVE",
                        json.writeValueAsString(new Schedule(schedule.plan(), next, schedule.maker())),
                        num(row, "version"));
            }
            // Move past even an overdue schedule: other schedules must get a turn before revisiting it.
            if (attempts == limit) {
                break;
            }
        }
        cursors.advance(task, type, cursor.watermark(), Math.addExact(cursor.watermark(), 1),
                json.writeValueAsString(Map.of("submittedAfter", submittedAfter, "configAfter", after)));
        return created;
    }

    @Override
    public String submitReceipt(
            String plan, LocalDate day, String evidence, String actor, String reason) {
        audit(actor, reason);
        require(evidence != null && !evidence.isBlank(), "POINTS_PLAN_RECEIPT_REQUIRED");
        var row = locked(plan, day);
        var period = json.readValue(text(row, "payload_json"), Period.class);
        if (period.request() != null) {
            require(
                    evidence.equals(period.request().evidenceNo())
                            && actor.equals(period.request().submittedBy()),
                    "POINTS_PLAN_RECEIPT_CONFLICT");
            return period.request().requestNo();
        }
        require("AWAITING_RECEIPT".equals(row.get("state")), "POINTS_PLAN_PERIOD_STATE_CONFLICT");
        var request = request(period.plan(), day, evidence, actor);
        config.submit(request, json.writeValueAsString(new Envelope(request, null)));
        store.savePlan(
                period.plan().typeCode(),
                plan,
                day.toString(),
                "SUBMITTED",
                json.writeValueAsString(
                        new Period(period.plan(), day, request, new Decision(actor, reason))),
                num(row, "version"));
        return request.requestNo();
    }

    @Override
    public void cancelPeriod(String plan, LocalDate day, String actor, String reason) {
        audit(actor, reason);
        var row = locked(plan, day);
        var period = json.readValue(text(row, "payload_json"), Period.class);
        if ("CANCELLED".equals(row.get("state"))) {
            require(
                    new Decision(actor, reason).equals(period.decision()),
                    "POINTS_PLAN_EVENT_CONFLICT");
            return;
        }
        require("AWAITING_RECEIPT".equals(row.get("state")), "POINTS_PLAN_PERIOD_STATE_CONFLICT");
        store.savePlan(
                period.plan().typeCode(),
                plan,
                day.toString(),
                "CANCELLED",
                json.writeValueAsString(
                        new Period(period.plan(), day, null, new Decision(actor, reason))),
                num(row, "version"));
    }

    private long settleSubmitted(String type, long after, int limit) {
        var page = store.plans(type, after, limit, false);
        if (page.isEmpty() && after > 0) {
            after = 0;
            page = store.plans(type, after, limit, false);
        }
        for (var row : page) {
            var period = json.readValue(text(row, "payload_json"), Period.class);
            var request = config.request(period.request().requestNo());
            if (request != null) {
                String state = text(request, "state");
                if (Set.of("APPROVED", "REJECTED", "CANCELLED").contains(state)) {
                    store.savePlan(type, period.plan().planNo(), period.day().toString(),
                            "APPROVED".equals(state) ? "COMPLETED" : "CANCELLED",
                            text(row, "payload_json"), num(row, "version"));
                }
            }
            after = num(row, "id");
        }
        return after;
    }

    private Map<String, Object> locked(String plan, LocalDate day) {
        require(day != null, "POINTS_PLAN_PERIOD_REQUIRED");
        var hint = store.plan(plan, day.toString());
        require(hint != null, "POINTS_PLAN_PERIOD_UNKNOWN");
        config.lockTypeAndPool(text(hint, "type_code"));
        return store.lockPlan(plan, day.toString());
    }

    private PoolRequest request(Plan plan, LocalDate day, String evidence, String actor) {
        return new PoolRequest(
                PointsKeys.position("PL", plan.typeCode(), plan.planNo() + ":" + day),
                plan.typeCode(),
                plan.action(),
                plan.amountMinor(),
                actor,
                evidence);
    }

    private void audit(String actor, String reason) {
        require(
                actor != null && !actor.isBlank() && reason != null && !reason.isBlank(),
                "POINTS_PLAN_AUDIT_REQUIRED");
    }

    private long num(Map<String, Object> row, String key) {
        return ((Number) row.get(key)).longValue();
    }

    private String text(Map<String, Object> row, String key) {
        return (String) row.get(key);
    }

    private void require(boolean condition, String reason) {
        if (!condition) {
            throw new IllegalArgumentException(reason);
        }
    }

    private record SaveIntent(String eventNo, Plan plan, String actor, String reason) {}
}
