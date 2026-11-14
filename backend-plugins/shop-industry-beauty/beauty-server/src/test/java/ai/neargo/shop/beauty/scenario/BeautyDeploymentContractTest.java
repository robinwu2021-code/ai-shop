package ai.neargo.shop.beauty.scenario;

import static org.assertj.core.api.Assertions.assertThat;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import ai.neargo.shop.auth.AbstractTokenAuthFilter;
import ai.neargo.shop.beauty.server.BeautyApplication;
import ai.neargo.shop.beauty.server.config.BeautyServerConfiguration;
import ai.neargo.shop.svc.InternalHttp;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.web.FilterChainProxy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.SpringBootApplication;

class BeautyDeploymentContractTest {
    private static final String TOKEN = "beauty-unit-test-service-token";
    private final WebApplicationContextRunner runner = new WebApplicationContextRunner()
            .withInitializer(new ConfigDataApplicationContextInitializer())
            .withUserConfiguration(BeautyApplication.class)
            .withPropertyValues("spring.autoconfigure.exclude="
                    + "org.springframework.boot.data.redis.autoconfigure.DataRedisAutoConfiguration,"
                    + "org.springframework.boot.data.redis.autoconfigure.DataRedisRepositoriesAutoConfiguration");

    @Test
    void disabledBeautyStillExposesNotReadyHealthWithoutPersistence() {
        runner.withPropertyValues("shop.beauty.enabled=false", "shop.services.internal-token=" + TOKEN).run(context -> {
            assertThat(context).hasNotFailed();
            mvc(context).perform(get("/actuator/health/readiness")).andExpect(status().isServiceUnavailable());
        });
    }

    @Test
    void standaloneServerHasNoPublicClientMappings() {
        assertThat(getClass().getClassLoader().getResource(
                "ai/neargo/shop/beauty/config/BeautyWebAutoConfiguration.class")).isNull();
        runner.withPropertyValues("spring.profiles.active=api,ops", "shop.services.internal-token=" + TOKEN)
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    var routes = context.getBean("requestMappingHandlerMapping", RequestMappingHandlerMapping.class)
                            .getHandlerMethods().keySet()
                            .stream().flatMap(info -> info.getPatternValues().stream()).collect(Collectors.toSet());
                    assertThat(routes).contains("/internal/beauty/test-probe", "/internal/beauty/test-write-probe")
                            .noneMatch(path -> path.startsWith("/mp/") || path.startsWith("/biz/")
                                    || path.startsWith("/ops/"));
                    assertThat(context).hasSingleBean(SecurityFilterChain.class)
                            .doesNotHaveBean(UserDetailsService.class);
                    assertThat(context.getBeansOfType(AbstractTokenAuthFilter.class)).isEmpty();
                });
    }

    @Test
    void missingServiceKeyNeverAuthorizesInternalRequests() {
        runner.withPropertyValues("shop.services.internal-token=").run(context -> {
            assertThat(context).hasNotFailed();
            mvc(context).perform(get("/internal/beauty/test-probe").header(InternalHttp.TOKEN_HEADER, TOKEN))
                    .andExpect(status().isUnauthorized());
            mvc(context).perform(get("/actuator/health/readiness")).andExpect(status().isServiceUnavailable());
        });
    }

    @Test
    void serviceTokenProtectsInternalGetAndPostWithoutUserSession() {
        runner.withPropertyValues("shop.services.internal-token=" + TOKEN).run(context -> {
            assertThat(context).hasNotFailed();
            MockMvc mvc = mvc(context);
            mvc.perform(get("/internal/beauty/test-probe")).andExpect(status().isUnauthorized());
            mvc.perform(get("/internal/beauty/test-probe").header(InternalHttp.TOKEN_HEADER, "wrong"))
                    .andExpect(status().isUnauthorized());
            mvc.perform(get("/internal/beauty/test-probe").header(InternalHttp.TOKEN_HEADER, TOKEN))
                    .andExpect(status().isOk()).andExpect(content().json("{\"result\":\"ok\"}"));
            mvc.perform(post("/internal/beauty/test-write-probe").header(InternalHttp.TOKEN_HEADER, TOKEN))
                    .andExpect(status().isOk());
            mvc.perform(get("/mp/beauty-trap").header(InternalHttp.TOKEN_HEADER, TOKEN))
                    .andExpect(status().isUnauthorized());
            assertThat(context.getBean(SecurityFilterChain.class).getFilters())
                    .noneMatch(filter -> filter instanceof AbstractTokenAuthFilter);
        });
    }

    @Test
    void businessReadinessStaysBlockedUntilSchemaAndPortsAreWired() {
        runner.withPropertyValues("shop.services.internal-token=" + TOKEN).run(context -> {
            assertThat(context).hasNotFailed();
            mvc(context).perform(get("/actuator/health/readiness"))
                    .andExpect(status().isServiceUnavailable())
                    .andExpect(content().json("{\"status\":\"OUT_OF_SERVICE\"}"));
            assertThat(context.getEnvironment().getProperty("spring.flyway.enabled", Boolean.class)).isFalse();
            assertThat(context.getEnvironment().getProperty("shop.beauty.deployment")).isEqualTo("standalone");
        });
    }

    private MockMvc mvc(WebApplicationContext context) {
        return MockMvcBuilders.webAppContextSetup(context)
                .addFilters(context.getBean(FilterChainProxy.class)).build();
    }

    @Test
    void standaloneDoesNotAutoConfigureUserSessions() {
        assertThat(BeautyApplication.class.getAnnotation(SpringBootApplication.class).excludeName())
                .contains("ai.neargo.common.data.DataAutoConfiguration",
                        "ai.neargo.common.security.SecurityAutoConfiguration",
                        "ai.neargo.common.security.SessionStoreAutoConfiguration",
                        "ai.neargo.common.security.rbac.RbacAutoConfiguration",
                        "org.springframework.boot.security.autoconfigure.UserDetailsServiceAutoConfiguration");
    }
    @Test
    void standaloneRejectsEmbeddedDeploymentBeforePersistence() {
        new ApplicationContextRunner().withUserConfiguration(BeautyServerConfiguration.class)
                .withPropertyValues("shop.beauty.enabled=true", "shop.beauty.deployment=embedded")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure())
                            .hasRootCauseMessage("beauty-server requires standalone deployment");
                });
    }

    @Test
    void standaloneAcceptsItsExplicitDeployment() {
        new ApplicationContextRunner().withUserConfiguration(BeautyServerConfiguration.class)
                .withPropertyValues("shop.beauty.enabled=true", "shop.beauty.deployment=standalone")
                .run(context -> assertThat(context).hasNotFailed());
    }

}
