package ai.neargo.shop.pay.storedvalue.policy;

import ai.neargo.shop.spi.storedvalue.StoredValuePolicies.*;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class StoreIssuancePolicyTest {
    private final StoredValueIssuePolicyEvaluator evaluator = new StoredValueIssuePolicyEvaluator();
    private final StoreScope store = new StoreScope("MAIN", "M1", "S1", "B1", "CNY");

    @Test void strictestPolicyAndNoApprovalBypass() {
        var effective = evaluator.evaluate(store, List.of(
                policy("P1", "V1", "SPACE", "M1", rules(true, false, 3000)),
                policy("P2", "V2", "BRAND_STORES", "B1", rules(true, true, 2000))));
        assertThat(effective.rules().maxBonusBps()).isEqualTo(2000);
        assertThat(effective.rules().requireApproval()).isTrue();
        var blocked = evaluator.check(effective, new IssueRequest(10000, 3000, 30, 0, 0));
        assertThat(blocked.allowed()).isFalse();
        assertThat(blocked.blockers()).contains("BONUS_RATIO_EXCEEDED");
        assertThat(blocked.approvalRequired()).isFalse();
        var permitted = evaluator.check(effective, new IssueRequest(10000, 2000, 30, 0, 0));
        assertThat(permitted.allowed()).isTrue();
        assertThat(permitted.approvalRequired()).isTrue();
    }

    @Test void sourceOrderIsCanonicalAndPolicyVersionChangeInvalidatesHash() {
        var root = policy("P1", "V1", "SPACE", "M1", rules(true, false, 3000));
        var child = policy("P2", "V2", "STORE", "S1", rules(true, false, 2000));
        var before = evaluator.evaluate(store, List.of(root, child));
        assertThat(evaluator.evaluate(store, List.of(child, root))).isEqualTo(before);
        assertThat(evaluator.evaluate(store, List.of(root,
                policy("P2", "V3", "STORE", "S1", rules(true, false, 2000)))).hash()).isNotEqualTo(before.hash());
        assertThat(evaluator.evaluate(new StoreScope("MAIN", "M1", "S2", "B1", "CNY"), List.of(root)).hash())
                .isNotEqualTo(evaluator.evaluate(store, List.of(root)).hash());
    }

    @Test void missingRootAndDisabledParentFailClosed() {
        var noRoot = evaluator.evaluate(store, List.of(policy("P2", "V2", "STORE", "S1", rules(true, false, 2000))));
        assertThat(evaluator.check(noRoot, new IssueRequest(10000, 0, 1, 0, 0)).blockers())
                .contains("ISSUE_POLICY_NOT_CONFIGURED");
        var disabled = evaluator.evaluate(store, List.of(
                policy("P1", "V1", "SPACE", "M1", rules(false, false, 3000)),
                policy("P2", "V2", "STORE", "S1", rules(true, false, 2000))));
        assertThat(evaluator.check(disabled, new IssueRequest(10000, 0, 1, 0, 0)).blockers()).contains("ISSUANCE_DISABLED");
    }

    @Test void refusesCrossTenantCurrencyScopeAndDuplicateSources() {
        var root = policy("P1", "V1", "SPACE", "M1", rules(true, false, 3000));
        assertThatThrownBy(() -> evaluator.evaluate(store, List.of(root, root))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> evaluator.evaluate(store, List.of(policy("P2", "V2", "STORE", "S2", root.rules()))))
                .isInstanceOf(IllegalArgumentException.class);
        var foreign = new PolicyVersion("OTHER", "M1", "P1", "V1", "SPACE", "M1", "CNY", root.rules());
        assertThatThrownBy(() -> evaluator.evaluate(store, List.of(foreign))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> evaluator.evaluate(new StoreScope("MAIN", "M1", "S1", "B1", "USD"), List.of(root)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test void ratioUsesExactIntegerComparisonWithoutRoundingOrOverflow() {
        var max = new Rules(true, false, 2000, Long.MAX_VALUE, Long.MAX_VALUE, 365, Long.MAX_VALUE);
        var policy = evaluator.evaluate(store, List.of(policy("P1", "V1", "SPACE", "M1", max)));
        assertThat(evaluator.check(policy, new IssueRequest(1, 1, 1, 0, 0)).blockers()).contains("BONUS_RATIO_EXCEEDED");
        assertThat(evaluator.check(policy, new IssueRequest(Long.MAX_VALUE, Long.MAX_VALUE, 1, 0, 0)).blockers())
                .contains("BONUS_RATIO_EXCEEDED", "OUTSTANDING_LIMIT_EXCEEDED");
    }

    @Test void outstandingIncludesBonusAndAllReservedCommitments() {
        var effective = evaluator.evaluate(store, List.of(policy("P1", "V1", "SPACE", "M1",
                new Rules(true, false, 2000, 10000, 2000, 30, 15000))));
        assertThat(evaluator.check(effective, new IssueRequest(10000, 2000, 30, 2000, 1000)).allowed()).isTrue();
        assertThat(evaluator.check(effective, new IssueRequest(10000, 2000, 30, 2000, 1001)).blockers())
                .contains("OUTSTANDING_LIMIT_EXCEEDED");
        assertThat(evaluator.check(effective, new IssueRequest(10001, 2001, 31, 0, 0)).blockers())
                .contains("PAID_LIMIT_EXCEEDED", "BONUS_LIMIT_EXCEEDED", "ACTIVE_DAYS_EXCEEDED");
    }

    @Test void childCannotRelaxParentOrRemoveRequiredApproval() {
        var parent = rules(true, true, 2000);
        assertThat(evaluator.tightens(rules(true, true, 1000), parent)).isTrue();
        assertThat(evaluator.tightens(rules(true, true, 3000), parent)).isFalse();
        assertThat(evaluator.tightens(rules(true, false, 1000), parent)).isFalse();
        assertThat(evaluator.tightens(rules(true, true, 1000), rules(false, true, 2000))).isFalse();
    }

    private Rules rules(boolean allow, boolean approval, int bps) {
        return new Rules(allow, approval, bps, 100000, 100000, 365, 1000000);
    }
    private PolicyVersion policy(String no, String version, String type, String target, Rules rules) {
        return new PolicyVersion("MAIN", "M1", no, version, type, target, "CNY", rules);
    }
}
