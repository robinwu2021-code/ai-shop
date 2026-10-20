package ai.neargo.shop.pay.migration.service;

import ai.neargo.shop.pay.entitlement.policy.EntitlementRuleValidator;
import ai.neargo.shop.spi.entitlement.EntitlementDefinition;
import ai.neargo.shop.spi.entitlement.EntitlementDefinition.RedemptionScope;
import ai.neargo.shop.spi.entitlement.EntitlementDefinition.Validity;
import ai.neargo.shop.spi.pay.MarketPort.MarketBrief;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import tools.jackson.databind.JsonNode;

/**
 * Read-only, deterministic assessment of authenticated V2 source evidence.
 * A translated rule is never an import authorization or a reconstructed payment fact.
 */
public final class LegacyCouponMigrationNormalizer {
    private static final Instant MIN_TIME = Instant.parse("1000-01-01T00:00:00Z");
    private static final Instant MAX_TIME = Instant.parse("9999-12-31T23:59:59.999Z");

    public Report normalize(String stageNo, JsonNode manifest, MarketBrief market, Instant at) {
        if (stageNo == null || stageNo.isBlank() || at == null) {
            throw new IllegalArgumentException("Assessment identity and instant are required");
        }
        var blockers = new LinkedHashSet<String>();
        var notices = new LinkedHashSet<String>();
        var items = new ArrayList<InstancePlan>();
        if (manifest == null || !manifest.isObject() || manifest.path("manifestVersion").asInt() != 2) {
            return report(stageNo, null, items, notices, Set.of("FULL_MANIFEST_REQUIRED"));
        }
        var root = manifest.path("template");
        var facts = manifest.path("assessmentFacts");
        String source = manifest.path("source").path("sourceSystem").asText();
        boolean legacy = "MKT_LEGACY".equals(source);
        if (!legacy && !"PMT".equals(source)) blockers.add("UNSUPPORTED_SOURCE");
        if (facts.path("version").asInt() != 1 || !facts.path("entityActive").isBoolean()
                || !facts.path("entityActive").asBoolean()) blockers.add("MERCHANT_FACTS_UNAVAILABLE");
        if (facts.path("legacyProgramReferenceCount").asLong(0)>0) blockers.add("LEGACY_PROGRAM_CONVERSION_REQUIRED");
        Long peerTemplates = integer(facts, "peerTemplateCount");
        Long peerInstances = integer(facts, "peerInstanceCount");
        if (peerTemplates == null || peerInstances == null || peerTemplates < 0 || peerInstances < 0) {
            blockers.add("SOURCE_COLLISION_FACTS_UNAVAILABLE");
        } else if (peerTemplates > 0 || peerInstances > 0) {
            blockers.add("SOURCE_NUMBER_COLLISION");
        }
        boolean marketValid = market != null && market.enabled() && "CNY".equals(market.currency())
                && market.currencyScale() == 2 && market.market().equals(facts.path("market").asText())
                && validZone(market.timeZone());
        if (!marketValid) blockers.add("MARKET_FACTS_UNAVAILABLE");
        if (!"MERCHANT".equals(root.path("funder").asText()) || root.path("entityNo").asText().isBlank()) {
            blockers.add("UNSUPPORTED_FUNDER");
        }
        if (!"ACTIVE".equals(root.path("status").asText())) blockers.add("LEGACY_TEMPLATE_NOT_ACTIVE");
        if (deleted(root)) blockers.add("DELETED_TEMPLATE");
        for (String field : List.of("benefitValue", "benefitCapMinor", "minAmountMinor", "faceMinor",
                "discountRate", "thresholdMinor", "maxDiscountMinor", "timesTotal", "validDays")) {
            if (!root.path(field).isNull() && !root.path(field).isMissingNode() && integer(root, field) == null) {
                blockers.add("INVALID_NUMERIC_FIELD");
            }
        }
        for (String field : List.of("benefitCapMinor", "maxDiscountMinor", "minAmountMinor", "thresholdMinor")) {
            Long value = integer(root, field);
            if (value != null && value < 0) blockers.add("INVALID_NUMERIC_FIELD");
        }
        String templateNo = root.path("couponNo").asText();
        if (templateNo.isBlank() || !templateNo.equals(manifest.path("source").path("sourceNo").asText())) {
            blockers.add("SOURCE_BINDING_MISMATCH");
        }
        if (!legacy && !"ORDER".equals(root.path("redeemMode").asText())) {
            blockers.add("LEGACY_REDEMPTION_BRIDGE_REQUIRED");
        }
        if (!legacy && !Set.of("CENTER", "TARGETED", "ACTIVITY").contains(root.path("issueMode").asText())) {
            blockers.add("UNSUPPORTED_ISSUE_MODE");
        }
        String benefit = legacy ? switch (root.path("type").asText()) {
            case "FULL_CUT" -> "CASH";
            case "DISCOUNT" -> "PERCENT";
            default -> "";
        } : root.path("benefitMode").asText();
        if (!Set.of("CASH", "PERCENT").contains(benefit)) blockers.add("UNSUPPORTED_BENEFIT");
        Long configuredTotal = integer(root, "timesTotal");
        int total = legacy || root.path("timesTotal").isNull() || root.path("timesTotal").isMissingNode()
                ? 1 : configuredTotal != null && configuredTotal <= Integer.MAX_VALUE
                && configuredTotal >= Integer.MIN_VALUE ? (int)Math.max(configuredTotal, 1) : 0;
        if (total == 0) blockers.add("INVALID_UNIT_COUNTER");
        if (!legacy && !"ALL".equals(root.path("scopeType").asText("ALL"))) {
            notices.add("LEGACY_ORDER_SCOPE_NOT_ENFORCED");
        }
        if (!legacy && !root.path("minQty").isNull() && !root.path("minQty").isMissingNode()) {
            notices.add("LEGACY_ORDER_MIN_QTY_NOT_ENFORCED");
        }
        Instant rootFrom = time(root, "startAt", false);
        Instant rootUntil = time(root, "endAt", true);
        Validity baseWindow = null;
        if (legacy || "ABSOLUTE".equals(root.path("validityMode").asText())) {
            if (validWindow(rootFrom, rootUntil)) {
                baseWindow = absolute(rootFrom, rootUntil, marketValid ? market.timeZone() : "UTC");
            } else blockers.add("INVALID_TEMPLATE_VALIDITY");
        } else if ("RELATIVE".equals(root.path("validityMode").asText())) {
            Long days = integer(root, "validDays");
            if (days != null && days > 0 && days <= Integer.MAX_VALUE) {
                baseWindow = new Validity("RELATIVE", null, null, days.intValue(), "GRANTED",
                        "ROLLING_DAYS", "UTC");
                notices.add("LEGACY_RELATIVE_FIXED_24H");
                notices.add("LEGACY_RELATIVE_INCLUSIVE_ENDPOINT");
            } else blockers.add("INVALID_TEMPLATE_VALIDITY");
        } else blockers.add("INVALID_TEMPLATE_VALIDITY");

        EntitlementDefinition definition = null;
        if (marketValid && baseWindow != null && total > 0) {
            definition = definition(root, legacy, benefit, total, market.currency(), baseWindow);
            if (!validDefinition(definition)) {
                blockers.add("INVALID_TARGET_DEFINITION");
                definition = null;
            }
        }
        var uses = new HashMap<String, Long>();
        var seenApplications = new HashSet<String>();
        var historyInstances = new HashSet<String>();
        var recipients = new HashMap<String, String>();
        for (var instance : manifest.path("instances")) {
            recipients.put(instance.path("userCouponNo").asText(), instance.path("userNo").asText());
        }
        if (!manifest.path("applications").isArray() || !manifest.path("instances").isArray()) {
            blockers.add("INCOMPLETE_MANIFEST");
        }
        boolean hasHistory = manifest.path("historyAvailable").isBoolean()
                && manifest.path("historyAvailable").asBoolean();
        if (!legacy && !hasHistory) blockers.add("SOURCE_HISTORY_UNAVAILABLE");
        for (var use : manifest.path("applications")) {
            String no = use.path("applyNo").asText();
            String instance = use.path("promoNo").asText();
            historyInstances.add(instance);
            String recipient = use.path("userNo").asText();
            if (recipient.isBlank() || !recipient.equals(recipients.get(instance))) {
                blockers.add("INVALID_HISTORY_RECIPIENT");
            }
            if (no.isBlank() || !seenApplications.add(no) || instance.isBlank()
                    || !"COUPON".equals(use.path("promoType").asText())
                    || !root.path("entityNo").asText().equals(use.path("entityNo").asText())) {
                blockers.add("INVALID_HISTORY_BINDING");
            }
            if (integer(use, "amountMinor") == null || integer(use, "amountMinor") < 0
                    || time(use, "appliedAt", false) == null) blockers.add("INVALID_HISTORY_FACT");
            boolean reverted = !use.path("revertedAt").isNull() && !use.path("revertedAt").isMissingNode();
            if (reverted && time(use, "revertedAt", false) == null) blockers.add("INVALID_HISTORY_FACT");
            if (!reverted) uses.merge(instance, 1L, Long::sum);
        }
        var seenInstances = new HashSet<String>();
        for (var instance : manifest.path("instances")) {
            String no = instance.path("userCouponNo").asText();
            if (no.isBlank() || !seenInstances.add(no) || !templateNo.equals(instance.path("couponNo").asText())
                    || instance.path("userNo").asText().isBlank()
                    || (!legacy && !root.path("entityNo").asText().equals(instance.path("entityNo").asText()))) {
                blockers.add("INVALID_INSTANCE_BINDING");
            }
            String status = instance.path("status").asText();
            Long rawUsed = integer(instance, "timesUsed");
            long used = legacy ? ("USED".equals(status) ? 1 : 0)
                    : instance.path("timesUsed").isNull() || instance.path("timesUsed").isMissingNode() ? 0
                    : rawUsed == null ? -1 : rawUsed;
            if (used < 0 || used > total) blockers.add("INVALID_UNIT_COUNTER");
            if (!Set.of("UNUSED", "USED", "EXPIRED").contains(status)) blockers.add("UNSUPPORTED_INSTANCE_STATUS");
            if (("USED".equals(status) && used != total) || ("UNUSED".equals(status) && used >= total)) {
                blockers.add("STATUS_COUNTER_MISMATCH");
            }
            if (legacy && used > 0) blockers.add("SOURCE_HISTORY_UNAVAILABLE");
            if (!legacy && hasHistory && uses.getOrDefault(no, 0L) != used) blockers.add("HISTORY_COUNTER_MISMATCH");
            Instant from = time(instance, "receivedAt", false);
            if (legacy && from != null && rootFrom != null && from.isBefore(rootFrom)) from = rootFrom;
            Instant until = legacy ? rootUntil : time(instance, "expireAt", true);
            if (!validWindow(from, until)) blockers.add("INVALID_INSTANCE_VALIDITY");
            if ("EXPIRED".equals(status) && until != null && at.isBefore(until)) {
                blockers.add("EXPIRED_STATUS_WINDOW_MISMATCH");
            }
            EntitlementDefinition historical = definition == null || !validWindow(from, until) ? null
                    : definition(root, legacy, benefit, total, market.currency(), absolute(from, until, market.timeZone()));
            if (historical != null && !validDefinition(historical)) {
                blockers.add("INVALID_HISTORICAL_DEFINITION");
                historical = null;
            }
            items.add(new InstancePlan(no, deleted(instance), status, used >= 0 && used <= total ? total - used : 0,
                    from, until, historical));
        }
        if (!seenInstances.containsAll(historyInstances)) blockers.add("INVALID_HISTORY_BINDING");
        var issuance = new LegacyCouponIssuanceAssessor().assess(manifest, definition, items, at);
        blockers.addAll(issuance.blockers());
        if (!legacy && total > 1) notices.add("LEGACY_BUDGET_EXCLUDES_REPEAT_USES");
        return new Report(stageNo, blockers.isEmpty() ? "NORMALIZED" : "BLOCKED", false, definition,
                items, List.copyOf(notices), List.copyOf(blockers), issuance);
    }

    private static EntitlementDefinition definition(JsonNode root, boolean legacy, String benefit, int total,
                                                     String currency, Validity validity) {
        return new EntitlementDefinition(root.path("title").asText(), "ORDER_COUPON", root.path("funder").asText(),
                currency, benefit, integer(root, legacy ? "PERCENT".equals(benefit) ? "discountRate" : "faceMinor"
                : "benefitValue"), "PERCENT".equals(benefit) ? integer(root, legacy ? "maxDiscountMinor" : "benefitCapMinor") : null, null,
                minimum(root, legacy), null, "COUNT", total, null,
                validity, new RedemptionScope("ALL", List.of()),
                legacy || "CENTER".equals(root.path("issueMode").asText()) ? List.of("CENTER", "TARGETED")
                        : "ACTIVITY".equals(root.path("issueMode").asText()) ? List.of("TARGETED", "ACTIVITY")
                        : List.of("TARGETED"));
    }
    private static long minimum(JsonNode root, boolean legacy) {
        Long value = integer(root, legacy ? "thresholdMinor" : "minAmountMinor");
        return value == null ? 0 : value;
    }
    private static boolean validDefinition(EntitlementDefinition definition) {
        try { new EntitlementRuleValidator().validate(definition); return true; }
        catch (ai.neargo.shop.common.BizException invalid) { return false; }
    }
    private static boolean validZone(String zone) {
        try { ZoneId.of(zone); return true; } catch (RuntimeException invalid) { return false; }
    }
    private static Long integer(JsonNode node, String name) {
        var value = node.path(name);
        return value.isIntegralNumber() && value.canConvertToLong() ? value.longValue() : null;
    }
    private static Instant time(JsonNode node, String name, boolean inclusiveEnd) {
        Long millis = integer(node, name);
        if (millis == null) return null;
        try {
            Instant result = Instant.ofEpochMilli(inclusiveEnd ? Math.addExact(millis, 1) : millis);
            return result.isBefore(MIN_TIME) || result.isAfter(MAX_TIME) ? null : result;
        } catch (RuntimeException invalid) { return null; }
    }
    private static boolean deleted(JsonNode node) {
        return node.path("deleted").asInt() != 0 || node.path("deleted").asBoolean(false);
    }
    private static boolean validWindow(Instant from, Instant until) {
        return from != null && until != null && from.isBefore(until);
    }
    private static Validity absolute(Instant from, Instant until, String zone) {
        return new Validity("ABSOLUTE", from, until, null, "GRANTED", "ROLLING_DAYS", zone);
    }
    private static Report report(String stageNo, EntitlementDefinition definition, List<InstancePlan> items,
                                 Set<String> notices, Set<String> blockers) {
        return new Report(stageNo, blockers.isEmpty() ? "NORMALIZED" : "BLOCKED", false, definition,
                List.copyOf(items), List.copyOf(notices), List.copyOf(blockers));
    }
    /** Deleted rows remain archival plans; no recipient identifiers or legacy bearer codes are exposed. */
    public record InstancePlan(String sourceInstanceNo, boolean archivalOnly, String sourceStatus, long remainingUnits,
                               Instant effectiveFrom, Instant effectiveUntil, EntitlementDefinition historicalDefinition) {}
    public record Report(String stageNo, String status, boolean importReady, EntitlementDefinition definition,
                         List<InstancePlan> instances, List<String> notices, List<String> blockers,
                         LegacyCouponIssuanceAssessor.Assessment issuance) {
        public Report(String stageNo, String status, boolean importReady, EntitlementDefinition definition,
                      List<InstancePlan> instances, List<String> notices, List<String> blockers) {
            this(stageNo,status,importReady,definition,instances,notices,blockers,null);
        }
        public Report {
            if (importReady) throw new IllegalArgumentException("Assessment is never an import permission");
            instances = List.copyOf(instances); notices = List.copyOf(notices); blockers = List.copyOf(blockers);
        }
    }
}
