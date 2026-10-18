package ai.neargo.shop.promotion.service;

import ai.neargo.shop.promotion.dto.ActivityActor;
import java.time.Instant;
import java.util.List;

/** Unified order effects are reported by currency; recharge and entitlement quantities remain separate metrics. */
public interface ActivityMarketingSummaryService {
    Summary summarize(ActivityActor actor, String storeNo, Instant from, Instant to);
    record Metric(String effectKind, String currency, long amountMinor, long eventCount) { }
    record Summary(Instant from, Instant to, List<Metric> metrics) {
        public Summary { metrics = List.copyOf(metrics); }
    }
}
