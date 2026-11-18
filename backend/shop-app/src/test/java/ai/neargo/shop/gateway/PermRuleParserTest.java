package ai.neargo.shop.gateway;

import static org.assertj.core.api.Assertions.assertThat;

import ai.neargo.shop.gateway.core.resource.PermRule;
import ai.neargo.shop.gateway.core.resource.PermRuleParser;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** 权限表达式解析：运营端现有的四种写法都要认得，认不得的必须明确说认不得（开发计划 M4 任务 4.3）。 */
class PermRuleParserTest {

    @Test
    @DisplayName("单个权限码")
    void singleCode() {
        PermRule rule = PermRuleParser.parse("@perm.can('pay:refund:review')").orElseThrow();
        assertThat(rule.codes()).containsExactly("pay:refund:review");
        assertThat(rule.test(Set.of("pay:refund:review")::contains)).isTrue();
        assertThat(rule.test(Set.of("pay:refund:view")::contains)).isFalse();
    }

    @Test
    @DisplayName("与、或")
    void andOr() {
        PermRule both = PermRuleParser.parse("@perm.can('a') and @perm.can('b')").orElseThrow();
        assertThat(both.test(Set.of("a")::contains)).isFalse();
        assertThat(both.test(Set.of("a", "b")::contains)).isTrue();

        PermRule either = PermRuleParser.parse("@perm.can('a') or @perm.can('b')").orElseThrow();
        assertThat(either.test(Set.of("b")::contains)).isTrue();
        assertThat(either.test(Set.of("c")::contains)).isFalse();
    }

    @Test
    @DisplayName("★★ 与或混合：括号与优先级由表达式解析器负责 —— (a 或 b 或 c) 且 d")
    void mixedKeepsGrouping() {
        PermRule rule = PermRuleParser.parse(
                "(@perm.can('a') or @perm.can('b') or @perm.can('c')) and @perm.can('d')").orElseThrow();
        assertThat(rule.codes()).containsExactly("a", "b", "c", "d");
        assertThat(rule.test(Set.of("b", "d")::contains)).isTrue();
        assertThat(rule.test(Set.of("a", "b", "c")::contains)).as("缺 d").isFalse();
        assertThat(rule.test(Set.of("d")::contains)).as("前三个一个都没有").isFalse();

        PermRule precedence = PermRuleParser.parse(
                "@perm.can('a') or @perm.can('b') and @perm.can('c')").orElseThrow();
        assertThat(precedence.test(Set.of("a")::contains)).as("and 比 or 绑得紧：a 单独就够").isTrue();
        assertThat(precedence.test(Set.of("b")::contains)).isFalse();
    }

    @Test
    @DisplayName("★★★ 认不得的表达式返回空，不猜：方法参数、别的 bean、别的方法、取反")
    void unknownShapesAreNotGuessed() {
        assertThat(PermRuleParser.parse("#request != null && #request.action() == 'X'")).isEmpty();
        assertThat(PermRuleParser.parse("@perm.can('a') and #id > 0")).isEmpty();
        assertThat(PermRuleParser.parse("@managementAccess.authenticated()")).isEmpty();
        assertThat(PermRuleParser.parse("@perm.canBiz('a')")).isEmpty();
        assertThat(PermRuleParser.parse("!@perm.can('a')")).isEmpty();
        assertThat(PermRuleParser.parse("hasAuthority('ROLE_X')")).isEmpty();
        assertThat(PermRuleParser.parse("@perm.can(")).as("语法错误").isEmpty();
    }
}
