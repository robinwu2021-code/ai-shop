package ai.neargo.shop.scenario;

import static org.assertj.core.api.Assertions.*;
import ai.neargo.shop.trade.order.points.earning.PointsEarningRules;
import ai.neargo.shop.trade.order.points.earning.PointsEarningRules.*;
import ai.neargo.shop.spi.points.PointsEarnPort.*;
import org.junit.jupiter.api.Test;
import java.util.*;

class PointsEarningRulesTest {
    private Rule rule(String scope, String mode, long value, long revision, Long active) {
        return new Rule("P", scope, revision, active, mode, value, mode, value, "ops", "note", 1);
    }
    @Test void nearestCategoryWinsAndZeroStopsInheritance() {
        var rules = List.of(rule("DEFAULT", "RATIO", 100, 1, 1L), rule("ROOT", "RATIO", 300, 2, 2L), rule("LEAF", "RATIO", 0, 3, 3L));
        var matched = PointsEarningRules.match(rules, List.of("LEAF", "ROOT"));
        assertThat(matched.scope()).isEqualTo("LEAF");
        assertThat(matched.rule().value()).isZero();
    }
    @Test void disabledOverrideInheritsAndUnpublishedDraftDoesNotAffectOrders() {
        var draft = new Rule("P", "ROOT", 3, 2L, "RATIO", 999, "RATIO", 200L, "ops", "draft", 1);
        var disabled = rule("LEAF", "RATIO", 900, 4, null);
        var matched = PointsEarningRules.match(List.of(draft, disabled), List.of("LEAF", "ROOT"));
        assertThat(matched.rule().value()).isEqualTo(200);
        assertThat(matched.revision()).isEqualTo(2);
    }
    @Test void missingCategoryUsesDefaultAndLegacyFixedIsPreserved() {
        var matched = PointsEarningRules.match(List.of(rule("DEFAULT", "FIXED", 7, 1, 1L)), List.of());
        assertThat(matched.rule()).isEqualTo(new EarnRule(RuleMode.FIXED, 7));
    }
    @Test void previewUsesCashValueAndIntegerConversion() {
        assertThat(PointsEarningRules.calculate(10000, 100, 1, 1)).isEqualTo(100);
        assertThat(PointsEarningRules.calculate(999, 125, 1, 1)).isEqualTo(12);
        assertThatThrownBy(() -> PointsEarningRules.calculate(100, 10001, 1, 1)).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void legacyOrderSnapshotWithoutTraceRemainsReadable() {
        var snapshot=new tools.jackson.databind.ObjectMapper().readValue("{\"schemaVersion\":1,\"fallbackRevision\":7,\"lines\":[]}", ai.neargo.shop.trade.order.points.PointsOrderPlacementCoordinator.EarningSnapshot.class);
        assertThat(snapshot.fallbackRevision()).isEqualTo(7);assertThat(snapshot.ruleTraces()).isEmpty();
    }

}
