package ai.neargo.shop.pay.points.pool;

import ai.neargo.shop.pay.points.model.PoolRequest;
import ai.neargo.shop.pay.points.model.PoolRequest.Action;

import java.time.LocalDate;

/** A schedule creates approval work, never bank receipts or automatic approval. */
public interface TopupPlanService {
    void save(String eventNo, Plan plan, String actor, String reason);

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

    record Schedule(Plan plan, LocalDate nextPeriod) {}

    record Period(Plan plan, LocalDate day, PoolRequest request, Decision decision) {}

    record Decision(String actor, String reason) {}
}
