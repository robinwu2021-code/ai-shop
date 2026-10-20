package ai.neargo.shop.food.scenario;

import static org.assertj.core.api.Assertions.assertThat;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import ai.neargo.shop.auth.AbstractTokenAuthFilter;
import ai.neargo.shop.food.server.FoodApplication;
import ai.neargo.shop.svc.InternalHttp;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
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

class FoodDeploymentContractTest {
    private static final String TOKEN = "food-unit-test-service-token";
    private final WebApplicationContextRunner runner = new WebApplicationContextRunner().withPropertyValues("shop.food.migration.mode=off", "spring.datasource.url=jdbc:h2:mem:food_server_skeleton", "spring.datasource.username=sa", "spring.datasource.password=")
            .withInitializer(new ConfigDataApplicationContextInitializer())
            .withUserConfiguration(FoodApplication.class)
            .withPropertyValues("spring.autoconfigure.exclude="
                    + "org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration,"
                    + "com.baomidou.mybatisplus.autoconfigure.MybatisPlusAutoConfiguration,"
                    + "org.springframework.boot.data.redis.autoconfigure.DataRedisAutoConfiguration,"
                    + "org.springframework.boot.data.redis.autoconfigure.DataRedisRepositoriesAutoConfiguration");

    @Test
    void disabledFoodStillExposesNotReadyHealthWithoutPersistence() {
        runner.withPropertyValues("shop.food.enabled=false", "shop.services.internal-token=" + TOKEN).run(context -> {
            assertThat(context).hasNotFailed().doesNotHaveBean(ai.neargo.shop.food.config.FoodSchemaReady.class)
                    .doesNotHaveBean(ai.neargo.shop.food.mapper.FoodMappers.TableAreaMapper.class);
            mvc(context).perform(get("/actuator/health/readiness")).andExpect(status().isServiceUnavailable());
        });
    }

    @Test
    void standaloneServerHasNoPublicClientMappings() {
        assertThat(getClass().getClassLoader().getResource(
                "ai/neargo/shop/food/config/FoodWebAutoConfiguration.class")).isNull();
        runner.withPropertyValues("spring.profiles.active=api,ops", "shop.services.internal-token=" + TOKEN)
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    var routes = context.getBean("requestMappingHandlerMapping", RequestMappingHandlerMapping.class).getHandlerMethods().keySet()
                            .stream().flatMap(info -> info.getPatternValues().stream()).collect(Collectors.toSet());
                    assertThat(routes).contains("/internal/food/test-probe", "/internal/food/test-write-probe")
                            .noneMatch(path -> path.startsWith("/mp/") || path.startsWith("/biz/")
                                    || path.startsWith("/ops/"));
                    assertThat(context).hasSingleBean(SecurityFilterChain.class).doesNotHaveBean(UserDetailsService.class);
                    assertThat(context.getBeansOfType(AbstractTokenAuthFilter.class)).isEmpty();
                });
    }

    @Test
    void missingServiceKeyNeverAuthorizesInternalRequests() {
        runner.withPropertyValues("shop.services.internal-token=").run(context -> {
            assertThat(context).hasNotFailed();
            mvc(context).perform(get("/internal/food/test-probe").header(InternalHttp.TOKEN_HEADER, TOKEN))
                    .andExpect(status().isUnauthorized());
            mvc(context).perform(get("/actuator/health/readiness")).andExpect(status().isServiceUnavailable());
        });
    }

    @Test
    void serviceTokenProtectsInternalGetAndPostWithoutUserSession() {
        runner.withPropertyValues("shop.services.internal-token=" + TOKEN).run(context -> {
            assertThat(context).hasNotFailed();
            MockMvc mvc = mvc(context);
            mvc.perform(get("/internal/food/test-probe")).andExpect(status().isUnauthorized());
            mvc.perform(get("/internal/food/test-probe").header(InternalHttp.TOKEN_HEADER, "wrong"))
                    .andExpect(status().isUnauthorized());
            mvc.perform(get("/internal/food/test-probe").header(InternalHttp.TOKEN_HEADER, TOKEN))
                    .andExpect(status().isOk()).andExpect(content().json("{\"result\":\"ok\"}"));
            mvc.perform(post("/internal/food/test-write-probe").header(InternalHttp.TOKEN_HEADER, TOKEN))
                    .andExpect(status().isOk());
            mvc.perform(get("/mp/food-trap").header(InternalHttp.TOKEN_HEADER, TOKEN))
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
            assertThat(context.getEnvironment().getProperty("shop.food.deployment")).isEqualTo("standalone");
        });
    }

    private MockMvc mvc(WebApplicationContext context) {
        return MockMvcBuilders.webAppContextSetup(context)
                .addFilters(context.getBean(FilterChainProxy.class)).build();
    }

    @Test
    void standaloneDoesNotAutoConfigureUserSessions() {
        assertThat(FoodApplication.class.getAnnotation(SpringBootApplication.class).excludeName())
                .contains("ai.neargo.common.data.DataAutoConfiguration", "ai.neargo.common.security.SecurityAutoConfiguration",
                        "ai.neargo.common.security.SessionStoreAutoConfiguration",
                        "ai.neargo.common.security.rbac.RbacAutoConfiguration",
                        "org.springframework.boot.security.autoconfigure.UserDetailsServiceAutoConfiguration");
    }
}
