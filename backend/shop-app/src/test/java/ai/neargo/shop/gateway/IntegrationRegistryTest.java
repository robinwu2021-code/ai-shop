package ai.neargo.shop.gateway;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ai.neargo.shop.gateway.core.integration.IntegrationGateFilter;
import ai.neargo.shop.gateway.core.integration.IntegrationRegistry;
import ai.neargo.shop.svc.ServiceLocator;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

/**
 * 网关代理的五类对象（零售、商城、餐饮、服务业、支付、库存）的统一接入配置（TDD-网关层与统一权限 §2.22）。不起 Spring。
 */
@DisplayName("网关 · 五类对象的接入配置")
class IntegrationRegistryTest {

    private static final ServiceLocator NONE = service -> Optional.empty();

    private static ServiceLocator only(String service, String url) {
        return name -> name.equals(service) ? Optional.of(url) : Optional.empty();
    }

    private static IntegrationRegistry registry(MockEnvironment env, ServiceLocator locator) {
        return new IntegrationRegistry(env, locator);
    }

    @Test
    @DisplayName("★★★ 不配新键：默认值取自现有开关（商城认 commerce.enabled），现有部署一个环境变量都不用改")
    void defaultsComeFromTheExistingSwitches() {
        MockEnvironment env = new MockEnvironment()
                .withProperty("commerce.enabled", "true")
                .withProperty("shop.inventory.enabled", "true")
                .withProperty("shop.pay.deployment", "standalone");
        IntegrationRegistry registry = registry(env, only("PAY", "http://pay:8083"));
        assertThat(registry.integrations()).containsOnlyKeys("retail", "commerce", "food", "beauty", "pay", "inventory");
        assertThat(registry.integrations().get("commerce").isEnabled()).isTrue();
        assertThat(registry.integrations().get("food").isEnabled()).isFalse();
        assertThat(registry.integrations().get("pay").getMode()).isEqualTo("standalone");
        assertThat(registry.integrations().get("inventory").isEnabled()).isTrue();
        assertThat(registry.integrations().get("retail").getMode()).isEqualTo("embedded");
    }

    @Test
    @DisplayName("★★★ 定位：按最长前缀、按段匹配（/ops/pay 不吞 /ops/payments 的地址）；不在任何对象下的地址不归谁")
    void ownerIsTheLongestSegmentPrefix() {
        IntegrationRegistry registry = registry(new MockEnvironment().withProperty("commerce.enabled", "true"), NONE);
        assertThat(registry.ownerOf("/biz/x/commerce/orders")).contains("commerce");
        assertThat(registry.ownerOf("/biz/goods")).contains("retail");
        assertThat(registry.ownerOf("/biz/payment/refund")).contains("pay");
        assertThat(registry.ownerOf("/ops/payments/refunds/R1/review")).contains("pay");
        assertThat(registry.ownerOf("/ops/pay")).contains("pay");
        assertThat(registry.ownerOf("/ops/payroll")).as("段边界：/ops/pay 不吞 /ops/payroll").contains("retail");
        assertThat(registry.ownerOf("/pay-ops/v1/refunds")).contains("pay");
        assertThat(registry.ownerOf("/internal/pay/v1/x")).isEmpty();
        assertThat(registry.ownerOf("/common/auth/login")).isEmpty();
    }

    @Test
    @DisplayName("★★★ 关掉的对象：它的前缀下一律 404，入口收不到；别的对象、零售照常")
    void disabledIntegrationAnswers404() throws Exception {
        IntegrationRegistry registry = registry(new MockEnvironment().withProperty("commerce.enabled", "false"), NONE);
        IntegrationGateFilter gate = new IntegrationGateFilter(registry);

        MockFilterChain blockedChain = new MockFilterChain();
        MockHttpServletResponse blocked = new MockHttpServletResponse();
        gate.doFilter(new MockHttpServletRequest("GET", "/biz/x/commerce/orders"), blocked, blockedChain);
        assertThat(blocked.getStatus()).isEqualTo(404);
        assertThat(blockedChain.getRequest()).as("入口收不到").isNull();

        MockFilterChain passChain = new MockFilterChain();
        MockHttpServletResponse pass = new MockHttpServletResponse();
        gate.doFilter(new MockHttpServletRequest("GET", "/biz/goods"), pass, passChain);
        assertThat(pass.getStatus()).isEqualTo(200);
        assertThat(passChain.getRequest()).isNotNull();
    }

    @Test
    @DisplayName("★★★ 显式配置覆盖默认值：只写一项，其余仍取默认")
    void explicitKeysOverrideDefaults() {
        MockEnvironment env = new MockEnvironment()
                .withProperty("commerce.enabled", "true")
                .withProperty("shop.gateway.integrations.commerce.enabled", "false")
                .withProperty("shop.gateway.integrations.pay.read-timeout", "PT9S");
        IntegrationRegistry registry = registry(env, NONE);
        assertThat(registry.integrations().get("commerce").isEnabled()).isFalse();
        assertThat(registry.integrations().get("commerce").getEnds()).containsKey("biz");
        assertThat(registry.integrations().get("pay").getReadTimeout()).hasSeconds(9);
        assertThat(registry.blocked("/biz/x/commerce/orders")).isTrue();
    }

    @Test
    @DisplayName("★★★ 启动校验：独立部署却没有服务地址，启动失败并指明是哪个对象")
    void standaloneWithoutAddressFailsAtStartup() {
        MockEnvironment env = new MockEnvironment().withProperty("shop.pay.deployment", "standalone");
        assertThatThrownBy(() -> registry(env, NONE))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("pay").hasMessageContaining("PAY");
        assertThat(registry(env, only("PAY", "http://pay:8083")).integrations().get("pay").getMode())
                .isEqualTo("standalone");
        MockEnvironment off = new MockEnvironment().withProperty("shop.food.deployment", "standalone");
        assertThat(registry(off, NONE).integrations().get("food").isEnabled())
                .as("没开的对象不查地址").isFalse();
    }

    @Test
    @DisplayName("★★ 启动校验：同一前缀分给两个对象、前缀出了这一端、零售被关或远程化、取值不认识，都启动失败")
    void misconfigurationsFailAtStartup() {
        assertThatThrownBy(() -> registry(new MockEnvironment()
                .withProperty("shop.gateway.integrations.beauty.ends.biz[0]", "/biz/x/food"), NONE))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("/biz/x/food");
        assertThatThrownBy(() -> registry(new MockEnvironment()
                .withProperty("shop.gateway.integrations.food.ends.mp[0]", "/biz/x/food2"), NONE))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("mp");
        assertThatThrownBy(() -> registry(new MockEnvironment()
                .withProperty("shop.gateway.integrations.retail.enabled", "false"), NONE))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("retail");
        assertThatThrownBy(() -> registry(new MockEnvironment()
                .withProperty("shop.gateway.integrations.retail.mode", "standalone"), NONE))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("retail");
        assertThatThrownBy(() -> registry(new MockEnvironment()
                .withProperty("shop.gateway.integrations.pay.entry", "remote"), NONE))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("entry");
        assertThatThrownBy(() -> registry(new MockEnvironment()
                .withProperty("shop.gateway.integrations.pay.ends.app[0]", "/app/pay"), NONE))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("app");
    }

    @Test
    @DisplayName("★★ 新接入的对象可以只靠配置加进来；没列的端不为它建任何路由")
    void newIntegrationFromConfigOnly() {
        MockEnvironment env = new MockEnvironment()
                .withProperty("shop.gateway.integrations.workflow.enabled", "false")
                .withProperty("shop.gateway.integrations.workflow.service", "WORKFLOW")
                .withProperty("shop.gateway.integrations.workflow.entry", "proxy")
                .withProperty("shop.gateway.integrations.workflow.resources", "seed")
                .withProperty("shop.gateway.integrations.workflow.ends.ops[0]", "/ops/x/workflow");
        IntegrationRegistry registry = registry(env, NONE);
        assertThat(registry.ownerOf("/ops/x/workflow/tasks")).contains("workflow");
        assertThat(registry.blocked("/ops/x/workflow/tasks")).isTrue();
        assertThat(registry.ownerOf("/biz/x/workflow/tasks")).as("没开放 B 端").contains("retail");
        assertThat(Map.copyOf(registry.integrations())).containsKey("workflow");
    }
}
