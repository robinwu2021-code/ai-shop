package ai.neargo.shop.pay.points.pool;

import ai.neargo.shop.pay.points.model.PoolRequest;
import ai.neargo.shop.pay.points.model.PoolRequest.Action;

import java.time.LocalDate;

/** A schedule creates approval work, never bank receipts or automatic approval. */
public interface TopupPlanService {
    void save(String eventNo, Plan plan, String actor, String reason);

    /**
     * Each call checks at most limit submitted periods and limit active schedules, and attempts at
     * most limit new/replayed periods. Durable rotating cursors ensure later rows receive a turn;
     * the return value counts newly created periods, not all work performed.
     */
    int tick(String typeCode, LocalDate asOf, int limit);

    String submitReceipt(
            String planNo, LocalDate period, String evidenceNo, String actor, String reason);

    void cancelPeriod(String planNo, LocalDate period, String actor, String reason);

    record Plan(
            String planNo,
            String typeCode,
            Action action,
            long amountMinor,
            LocalDate anchor,
            int everyDays,
            boolean enabled) {}

    record Schedule(Plan plan, LocalDate nextPeriod, String maker) {}

    record Period(Plan plan, LocalDate day, PoolRequest request, Decision decision) {}

    record Decision(String actor, String reason) {}
}
