package ai.neargo.shop.gateway;

import static org.assertj.core.api.Assertions.assertThat;

import ai.neargo.shop.auth.BizPerms;
import ai.neargo.shop.auth.Perms;
import ai.neargo.shop.auth.Realm;
import ai.neargo.shop.gateway.core.function.ModuleOwnership;
import ai.neargo.shop.pay.access.PayAction;
import ai.neargo.shop.pay.access.policy.CapabilityMatrix;
import ai.neargo.shop.pay.channel.api.CallerType;
import ai.neargo.shop.payclient.PayDownstreamActions;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 功能点 → 支付动作的映射表（TDD-网关层与统一权限 AC17；开发计划 §2.6 任务 14）。不起 Spring。
 */
@DisplayName("网关 · 功能点到支付动作的映射")
class PayDownstreamActionsTest {

    private static final Map<Realm, CallerType> CALLER = Map.of(Realm.MERCHANT, CallerType.BIZ,
            Realm.OPERATOR, CallerType.OPS);

    @Test
    @DisplayName("★★★ 契约：每个码都是这一端登记过的功能点；每个动作在这一端调用方的能力矩阵里允许")
    void everyEntryIsARealPointAndAnAllowedAction() {
        List<String> problems = new ArrayList<>();
        for (Realm realm : List.of(Realm.MERCHANT, Realm.OPERATOR)) {
            Map<String, Set<PayAction>> table = PayDownstreamActions.table(realm);
            assertThat(table).as(realm + " 的表不该是空的").isNotEmpty();
            for (var entry : table.entrySet()) {
                if (!ModuleOwnership.codes(realm).contains(entry.getKey())) {
                    problems.add(realm + " " + entry.getKey() + " 不是登记过的功能点");
                }
                assertThat(entry.getValue()).as(entry.getKey() + " 至少对应一个动作").isNotEmpty();
                for (PayAction action : entry.getValue()) {
                    if (!CapabilityMatrix.allows(CALLER.get(realm), action)) {
                        problems.add(realm + " " + entry.getKey() + " → " + action + " 不在 "
                                + CALLER.get(realm) + " 的能力矩阵里");
                    }
                }
            }
        }
        assertThat(problems).isEmpty();
        assertThat(PayDownstreamActions.table(Realm.CONSUMER)).as("C 端没有权限码").isEmpty();
    }

    @Test
    @DisplayName("★★★ 退款两格：发起只在 B 端退款码上，复核只在运营端复核码上，两端不串")
    void refundRequestAndReviewStayOnTheirOwnSide() {
        var actions = new PayDownstreamActions();
        assertThat(actions.actionsOf(Realm.MERCHANT, List.of(BizPerms.PAY_REFUND))).contains("REQUEST_REFUND");
        assertThat(actions.actionsOf(Realm.OPERATOR, List.of(Perms.PAY_REFUND_REVIEW))).containsExactly("REVIEW_REFUND");
        assertThat(PayDownstreamActions.table(Realm.MERCHANT).values()).noneMatch(set -> set.contains(PayAction.REVIEW_REFUND));
        assertThat(PayDownstreamActions.table(Realm.OPERATOR).values()).noneMatch(set -> set.contains(PayAction.REQUEST_REFUND));
        assertThat(actions.actionsOf(Realm.MERCHANT, List.of(BizPerms.PAY_ORDER_VIEW)))
                .as("只看收款单的人拿不到退款").doesNotContain("REQUEST_REFUND");
    }

    @Test
    @DisplayName("★★ 多个码取并集、去重、按名字排序；没写进表的码不带动作；同名的码在另一端不算")
    void unionSortedAndPerRealm() {
        var actions = new PayDownstreamActions();
        assertThat(actions.actionsOf(Realm.MERCHANT, List.of(BizPerms.PAY_QR, BizPerms.PAY_STATEMENT)))
                .containsExactly("CREATE_ONLINE", "QUERY", "READ_LEDGER");
        assertThat(actions.actionsOf(Realm.MERCHANT, List.of(BizPerms.PAY_STORE_METHOD, "biz:goods"))).isEmpty();
        assertThat(actions.actionsOf(Realm.MERCHANT, List.of(Perms.PAY_REFUND_REVIEW))).isEmpty();
    }
}
