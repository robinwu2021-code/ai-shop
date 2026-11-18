package ai.neargo.shop.gateway;

import static org.assertj.core.api.Assertions.assertThat;

import ai.neargo.shop.gateway.core.config.GatewayEndSwitch;
import ai.neargo.shop.gateway.core.config.GatewayEndSwitch.State;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

/** 一端开没开的三种状态（TDD-网关层与统一权限-开发计划 M2 任务 2.6）。 */
class GatewayEndSwitchTest {

    @Test
    @DisplayName("开关没配：跟着部署面走 —— 现有部署与只设 profile 的切片测试行为不变")
    void followsDeploymentProfileWhenUnset() {
        assertThat(GatewayEndSwitch.resolve(env("api"), "biz", "api")).isEqualTo(State.ON);
        assertThat(GatewayEndSwitch.resolve(env("ops"), "biz", "api")).isEqualTo(State.ABSENT);
        assertThat(GatewayEndSwitch.resolve(env(), "ops", "ops")).isEqualTo(State.ABSENT);
    }

    @Test
    @DisplayName("★★★ 入口还在而开关关了：必须是 CLOSED 而不是 ABSENT —— 不装链的话请求会绕过认证落到入口上")
    void explicitOffWhileDeployedIsClosed() {
        MockEnvironment env = env("api").withProperty("shop.gateway.biz.enabled", "false");
        assertThat(GatewayEndSwitch.resolve(env, "biz", "api")).isEqualTo(State.CLOSED);
    }

    @Test
    @DisplayName("开关关了且部署面也没带这一端：什么都不装")
    void explicitOffWhileNotDeployedIsAbsent() {
        MockEnvironment env = env("ops").withProperty("shop.gateway.biz.enabled", "false");
        assertThat(GatewayEndSwitch.resolve(env, "biz", "api")).isEqualTo(State.ABSENT);
    }

    @Test
    @DisplayName("开关显式打开：装链")
    void explicitOnIsOn() {
        MockEnvironment env = env("ops").withProperty("shop.gateway.biz.enabled", "true");
        assertThat(GatewayEndSwitch.resolve(env, "biz", "api")).isEqualTo(State.ON);
    }

    private static MockEnvironment env(String... profiles) {
        MockEnvironment env = new MockEnvironment();
        env.setActiveProfiles(profiles);
        return env;
    }
}
