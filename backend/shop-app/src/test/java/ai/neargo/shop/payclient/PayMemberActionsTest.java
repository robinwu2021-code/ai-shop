package ai.neargo.shop.payclient;

import static org.assertj.core.api.Assertions.assertThat;

import ai.neargo.shop.auth.BizPerms;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * B 端码 → near-pay 商户端动作（TDD-支付三端经网关透传与权限归属 §13.3、§14）。
 *
 * <p>导出物 {@code docs/api/pay-member-actions.json} 给 near-pay 的对账测试读。表改了先跑
 * {@code -Dpay.member-actions.update=true} 重写导出物，再一起提交。
 */
@DisplayName("支付 · B 端码与 near-pay 商户端动作对照")
class PayMemberActionsTest {

    private static final Path EXPORT = Path.of("../../docs/api/pay-member-actions.json");

    /** near-pay {@code MerchantRole} 的 14 个动作 + {@code BOOTSTRAP_ACTIONS} 里的 merchant.link（near-pay develop/s1 核对） */
    private static final Set<String> NEAR_PAY_ACTIONS = Set.of(
            "payment.create", "payment.query", "payment.close", "refund.apply", "refund.review", "device.activate",
            "qrcode.manage", "store.ledger.read", "statement.read", "onboarding.apply", "onboarding.query",
            "member.manage", "store.bind", "app.grant.query", "app.grant", "merchant.link");

    @Test
    @DisplayName("★★★ near-pay 的每个动作都有且只有一个码能给；店主（*）拿到全部")
    void everyActionCoveredOnce() {
        var owners = PayMemberActions.table().entrySet().stream()
                .flatMap(e -> e.getValue().stream().map(a -> Map.entry(a, e.getKey())))
                .collect(Collectors.groupingBy(Map.Entry::getKey, Collectors.mapping(Map.Entry::getValue, Collectors.toList())));
        assertThat(owners.keySet()).isEqualTo(NEAR_PAY_ACTIONS);
        assertThat(owners).allSatisfy((action, codes) -> assertThat(codes).as(action).hasSize(1));
        assertThat(PayMemberActions.actionsOf(Set.of("*"))).containsExactlyInAnyOrderElementsOf(NEAR_PAY_ACTIONS);
    }

    @Test
    @DisplayName("★★★ 只给店主的动作挂在不可勾选的码上：退款复核、设备激活、挂收款号、成员、建商户")
    void ownerOnlyActionsAreNotAssignable() {
        assertThat(PayMemberActions.table().get(BizPerms.STORE_ADMIN)).containsExactlyInAnyOrder(
                "refund.review", "device.activate", "store.bind", "member.manage", "merchant.link");
        assertThat(BizPerms.assignableCodes()).doesNotContain(BizPerms.STORE_ADMIN);
        PayMemberActions.table().keySet().stream().filter(c -> !BizPerms.STORE_ADMIN.equals(c))
                .forEach(c -> assertThat(BizPerms.assignableCodes()).as(c).contains(c));
    }

    @Test
    @DisplayName("预置角色换出来的动作：店长能收款、查单；店员只能查单；勾了 biz:finance 能进件")
    void presetRoles() {
        assertThat(PayMemberActions.actionsOf(BizPerms.of(Set.of(BizPerms.MANAGER))))
                .containsExactly("payment.close", "payment.create", "payment.query");
        assertThat(PayMemberActions.actionsOf(BizPerms.of(Set.of(BizPerms.CLERK)))).containsExactly("payment.query");
        assertThat(PayMemberActions.actionsOf(Set.of(BizPerms.FINANCE))).containsExactly("onboarding.apply", "onboarding.query");
        assertThat(PayMemberActions.actionsOf(Set.of("biz:order:view"))).isEmpty();
    }

    @Test
    @DisplayName("★★★ 导出物与代码一致（near-pay 对账读它）")
    void exportUpToDate() throws IOException {
        String expected = render();
        if (Boolean.getBoolean("pay.member-actions.update")) {
            Files.writeString(EXPORT, expected, StandardCharsets.UTF_8);
        }
        assertThat(Files.readString(EXPORT, StandardCharsets.UTF_8))
                .as("表改了：mvn … -Dtest=PayMemberActionsTest -Dpay.member-actions.update=true 重写 " + EXPORT)
                .isEqualTo(expected);
    }

    private static String render() {
        StringBuilder out = new StringBuilder();
        out.append("{\n");
        out.append("  \"source\": \"ai-shop backend/shop-gateway/.../payclient/PayMemberActions.java\",\n");
        out.append("  \"doc\": \"docs/technical/TDD-支付三端经网关透传与权限归属.md §13.3\",\n");
        out.append("  \"namespaces\": [\"mch:pay:\", \"biz:finance\", \"biz:store:admin\"],\n");
        out.append("  \"owner\": ").append(list(PayMemberActions.all())).append(",\n");
        out.append("  \"codes\": {\n");
        var rows = PayMemberActions.table().entrySet().stream()
                .map(e -> "    \"" + e.getKey() + "\": " + list(e.getValue())).toList();
        out.append(String.join(",\n", rows)).append("\n  }\n}\n");
        return out.toString();
    }

    private static String list(List<String> values) {
        return values.stream().map(v -> "\"" + v + "\"").collect(Collectors.joining(", ", "[", "]"));
    }
}
