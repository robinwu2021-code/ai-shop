package ai.neargo.shop.printing.center.template;

import ai.neargo.shop.print.api.*;
import ai.neargo.shop.print.api.dto.PrintConfigDtos.*;

import org.springframework.stereotype.Component;

import tools.jackson.databind.JsonNode;

import java.util.*;

@Component
public final class PrintRouteMatcher {
    public List<RouteMatch> match(String documentKey, JsonNode payload, List<RouteRule> rules) {
        var ordered =
                rules.stream()
                        .sorted(
                                Comparator.comparingInt(RouteRule::priority)
                                        .reversed()
                                        .thenComparing(RouteRule::ruleKey))
                        .toList();
        for (var r : ordered) {
            boolean matches = true;
            for (var p : r.match().all()) {
                JsonNode value = payload.path(p.field());
                if (value.isMissingNode()) value = payload.path("fields").path(p.field());
                if (!value.asText().equals(p.value())) matches = false;
            }
            if (matches)
                return r.targets().stream()
                        .map(
                                t ->
                                        new RouteMatch(
                                                documentKey,
                                                r.ruleKey(),
                                                t.printerNo(),
                                                t.copies(),
                                                t.required(),
                                                t.templateSelection()))
                        .toList();
        }
        throw new PrintFailure("print.routeUnmatched");
    }

    public void validate(List<RouteRule> rules) {
        if (rules == null || rules.isEmpty() || rules.size() > 100)
            throw new PrintFailure("print.invalidRoute");
        var keys = new HashSet<String>();
        for (var r : rules) {
            if (r.ruleKey() == null
                    || r.ruleKey().length() > 64
                    || !keys.add(r.ruleKey())
                    || r.match() == null
                    || r.match().all().size() > 10
                    || r.targets().isEmpty()
                    || r.targets().size() > 10) throw new PrintFailure("print.invalidRoute");
            for (var p : r.match().all())
                if (!Set.of("stationNo", "fulfillment", "categoryNo", "serviceType", "scene")
                                .contains(p.field())
                        || !"EQ".equals(p.op()))
                    throw new PrintFailure("print.routePredicateRejected");
            var targets = new HashSet<String>();
            for (var t : r.targets())
                if (t.targetKey() == null
                        || !targets.add(t.targetKey())
                        || t.copies() < 1
                        || t.copies() > 10
                        || t.templateSelection() == null
                        || !Set.of("STANDARD_58", "STANDARD_80")
                                .contains(t.templateSelection().variantCode()))
                    throw new PrintFailure("print.invalidRouteTarget");
        }
    }
}
