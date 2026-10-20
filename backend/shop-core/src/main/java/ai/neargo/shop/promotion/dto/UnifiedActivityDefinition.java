package ai.neargo.shop.promotion.dto;

import ai.neargo.shop.spi.entitlement.EntitlementDefinition.RedemptionScope;
import java.time.Instant;
import java.util.List;

/** Draft and published version use the same value shape; publication freezes the entire definition. */
public record UnifiedActivityDefinition(String name, String scene, String templateCode, int templateVersion,
        RedemptionScope storeParticipationScope, String audienceRef, Schedule schedule, Rule rule,
        Long quotaLimit, Long budgetLimitMinor, int perUserLimit, String currency) {
    public record Schedule(String type, Instant startAt, Instant endAt, String zone,
                           List<Integer> weekdays, String from, String to) {
        public Schedule { weekdays = weekdays == null ? List.of() : List.copyOf(weekdays); }
    }
    public record Trigger(String type, Long amountMinor, Integer quantity) { }
    public record Benefit(String kind, Long amountMinor, String templateVersionNo, String goodsNo,
                          Integer quantity) { }
    public record Rule(int schemaVersion, Trigger trigger, List<Benefit> benefits, List<Tier> tiers) {
        public Rule {
            benefits = benefits == null ? List.of() : List.copyOf(benefits);
            tiers = tiers == null ? List.of() : List.copyOf(tiers);
        }
    }
    public record Tier(String tierNo, long payAmountMinor, long bonusAmountMinor,
                       RedemptionScope redemptionScope) { }
}
