package ai.neargo.shop.platform;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** 版本比较（TDD-商户App问题回报与版本 V4）。 */
class AppVersionsTest {

    @Test
    @DisplayName("★★ App compareVersions 的用例逐条照抄（ai-shop-bapp v22_units_test.dart）")
    void compares_like_app_compare_versions() {
        assertThat(AppVersions.compare("0.1.0", "0.1.0")).isZero();
        assertThat(AppVersions.compare("0.9.0", "0.10.0")).isNegative();
        assertThat(AppVersions.compare("1.2", "1.1.9")).isPositive();
        assertThat(AppVersions.compare("3.4.0+812", "3.4.0")).isZero();
    }

    @Test
    @DisplayName("★★★ 括号里的构建号不参与比较：1.4.2 (20261108.1) 大于 1.4.1（App 现有规则会当成 1.4.0）")
    void build_suffix_in_parentheses_is_ignored() {
        assertThat(AppVersions.compare("1.4.2 (20261108.1)", "1.4.1")).isPositive();
        assertThat(AppVersions.compare("1.4.2 (20261108.1)", "1.4.2")).isZero();
        assertThat(AppVersions.compare("1.4 (7)", "1.4.0")).isZero();
    }

    @Test
    @DisplayName("★ 取不出前缀的不是版本")
    void non_versions_are_rejected() {
        assertThat(AppVersions.isVersion("")).isFalse();
        assertThat(AppVersions.isVersion(null)).isFalse();
        assertThat(AppVersions.isVersion("v1.0")).isFalse();
        assertThat(AppVersions.isVersion("1")).isTrue();
    }
}
