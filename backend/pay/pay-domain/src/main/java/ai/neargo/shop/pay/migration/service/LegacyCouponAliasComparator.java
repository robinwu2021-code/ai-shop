package ai.neargo.shop.pay.migration.service;

import ai.neargo.shop.pay.entitlement.service.EntitlementRecords;
import ai.neargo.shop.spi.pay.MarketPort.MarketBrief;
import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/** Strict current-facts comparison only; copied business numbers never establish identity or transfer ownership. */
public final class LegacyCouponAliasComparator {
    private final ObjectMapper json = new ObjectMapper();

    public Comparison compare(JsonNode pmt, JsonNode mkt, MarketBrief market, Instant at) {
        var differences = new LinkedHashSet<String>();
        if (pmt == null || mkt == null || !pair(pmt, mkt)) {
            return new Comparison("DIFFERENT", false, 0, null, List.of("EXACT_SOURCE_PAIR_REQUIRED"));
        }
        for (var source : List.of(pmt, mkt)) {
            var facts = source.path("assessmentFacts");
            if (!facts.path("peerTemplateCount").isIntegralNumber() || facts.path("peerTemplateCount").asLong() != 1
                    || !facts.path("peerInstanceCount").isIntegralNumber()
                    || facts.path("peerInstanceCount").asLong() != source.path("instances").size()) {
                differences.add("ADDITIONAL_SOURCE_COLLISION");
            }
            if (!source.path("applications").isArray() || !source.path("applications").isEmpty()) {
                differences.add("HISTORICAL_ALIAS_REQUIRES_PROVENANCE");
            }
            for (var instance : source.path("instances")) {
                if ("USED".equals(instance.path("status").asText())
                        || instance.path("timesUsed").asLong(0) != 0
                        || (!instance.path("usedAt").isNull() && !instance.path("usedAt").isMissingNode())
                        || (!instance.path("orderNo").isNull() && !instance.path("orderNo").isMissingNode())) {
                    differences.add("HISTORICAL_ALIAS_REQUIRES_PROVENANCE");
                }
            }
        }
        var normalizer = new LegacyCouponMigrationNormalizer();
        // Comparison context only. Neither source evidence nor its authenticated collision facts is rewritten.
        var primary = normalizer.normalize("ALIAS-PMT", withoutPairCollision(pmt), market, at);
        var shadow = normalizer.normalize("ALIAS-MKT", withoutPairCollision(mkt), market, at);
        if (!primary.blockers().isEmpty() || !shadow.blockers().isEmpty()) {
            differences.add("SOURCE_NOT_NORMALIZABLE");
        }
        if (primary.definition() == null || !primary.definition().equals(shadow.definition())) {
            differences.add("EFFECTIVE_RULES_DIFFER");
        }
        if (primary.issuance() == null || !primary.issuance().equals(shadow.issuance())) {
            differences.add("ISSUANCE_BASELINES_DIFFER");
        }
        var primaryItems = items(pmt, primary);
        var shadowItems = items(mkt, shadow);
        if (!primaryItems.equals(shadowItems)) differences.add("INSTANCE_FACTS_DIFFER");
        String hash = differences.isEmpty() ? EntitlementRecords.digest(json.writeValueAsString(
                List.of("LEGACY_ALIAS_COMPARISON_V1", pmt.path("source").path("sourceNo").asText(),
                        pmt.path("template").path("entityNo").asText(), primary.definition(),
                        primary.issuance(), primaryItems))) : null;
        return new Comparison(differences.isEmpty() ? "CANDIDATE_ALIAS" : "DIFFERENT", false,
                differences.isEmpty() ? primaryItems.size() : 0, hash, List.copyOf(differences));
    }

    private boolean pair(JsonNode pmt, JsonNode mkt) {
        String no = pmt.path("source").path("sourceNo").asText();
        String entity = pmt.path("template").path("entityNo").asText();
        return pmt.path("manifestVersion").asInt() == 2 && mkt.path("manifestVersion").asInt() == 2
                && "PMT".equals(pmt.path("source").path("sourceSystem").asText())
                && "MKT_LEGACY".equals(mkt.path("source").path("sourceSystem").asText())
                && "TEMPLATE".equals(pmt.path("source").path("sourceKind").asText())
                && "TEMPLATE".equals(mkt.path("source").path("sourceKind").asText())
                && !no.isBlank() && no.equals(mkt.path("source").path("sourceNo").asText())
                && !entity.isBlank() && entity.equals(mkt.path("template").path("entityNo").asText());
    }

    /** Private comparison copy; only the separately checked, exact companion collision is discounted. */
    private ObjectNode withoutPairCollision(JsonNode source) {
        ObjectNode copy = (ObjectNode) source.deepCopy();
        if (copy.path("assessmentFacts").isObject()) {
            ((ObjectNode) copy.path("assessmentFacts")).put("peerTemplateCount", 0).put("peerInstanceCount", 0);
        }
        return copy;
    }

    private Map<String, Object> items(JsonNode source, LegacyCouponMigrationNormalizer.Report report) {
        var recipients = new TreeMap<String, String>();
        for (var row : source.path("instances")) {
            recipients.put(row.path("userCouponNo").asText(), row.path("userNo").asText());
        }
        var result = new TreeMap<String, Object>();
        for (var row : report.instances()) {
            var fields = new TreeMap<String, Object>();
            fields.put("recipient", recipients.get(row.sourceInstanceNo()));
            fields.put("archivalOnly", row.archivalOnly());
            fields.put("status", row.sourceStatus());
            fields.put("remainingUnits", row.remainingUnits());
            fields.put("effectiveFrom", row.effectiveFrom());
            fields.put("effectiveUntil", row.effectiveUntil());
            fields.put("definition", row.historicalDefinition());
            result.put(row.sourceInstanceNo(), fields);
        }
        return result;
    }

    public record Comparison(String status, boolean importReady, long matchedInstanceCount,
                             String comparisonHash, List<String> differences) {
        public Comparison {
            if (importReady) throw new IllegalArgumentException("A comparison cannot authorize an import");
            differences = List.copyOf(differences);
        }
    }
}
