package ai.neargo.shop.food.scenario;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import ai.neargo.shop.auth.ConsumerTokenAuthFilter;
import ai.neargo.shop.auth.LoginUser;
import ai.neargo.shop.auth.TokenStore;
import ai.neargo.shop.auth.store.MemoryTokenStore;
import ai.neargo.shop.food.config.FoodWebAutoConfiguration;
import ai.neargo.shop.food.fixture.web.WebProbes;
import java.time.Duration;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.context.annotation.ImportCandidates;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.web.FilterChainProxy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

class FoodWebIsolationTest {

    private final WebApplicationContextRunner runner = new WebApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(FoodWebAutoConfiguration.class))
            .withUserConfiguration(HostScan.class);

    @Test
    void discoversRestaurantAutoConfiguration() {
        assertThat(ImportCandidates.load(AutoConfiguration.class, getClass().getClassLoader()).getCandidates())
                .contains("ai.neargo.shop.food.config.FoodWebAutoConfiguration");
    }

    @ParameterizedTest
    @ValueSource(strings = {"embedded", "standalone"})
    void loadWebWithoutFoodCore(String mode) {
        assertThat(getClass().getClassLoader().getResource(
                "ai/neargo/shop/food/config/FoodAutoConfiguration.class")).isNull();
        runner.withPropertyValues("shop.food.enabled=true", "shop.food.deployment=" + mode,
                        "spring.profiles.active=api")
                .run(context -> {
                    assertThat(context).hasNotFailed().hasSingleBean(FoodWebAutoConfiguration.class);
                    assertThat(routes(context.getBean(RequestMappingHandlerMapping.class)))
                            .containsExactlyInAnyOrder("/mp/food-test-probe", "/biz/food-test-probe", "/mp/retail-test-probe");
                });
    }

    @Test
    void disabledFoodRegistersNoComponents() {
        for (String profile : new String[] {"api", "ops"}) {
            for (String enabled : new String[] {"", "shop.food.enabled=false"}) {
                WebApplicationContextRunner configured = runner.withPropertyValues("spring.profiles.active=" + profile);
                if (!enabled.isEmpty()) {
                    configured = configured.withPropertyValues(enabled);
                }
                configured.run(context -> {
                    assertThat(context).hasNotFailed().doesNotHaveBean(FoodWebAutoConfiguration.class)
                            .doesNotHaveBean(WebProbes.MpProbe.class)
                            .doesNotHaveBean(WebProbes.BizProbe.class)
                            .doesNotHaveBean(WebProbes.OpsProbe.class);
                    assertThat(routes(context.getBean(RequestMappingHandlerMapping.class)))
                            .containsExactly("/mp/retail-test-probe");
                });
            }
        }
    }

    @Test
    void disableApiRoutesInOpsProfile() {
        runner.withPropertyValues("shop.food.enabled=true", "spring.profiles.active=ops")
                .run(context -> {
                    assertThat(context).hasNotFailed().hasSingleBean(FoodWebAutoConfiguration.class)
                            .doesNotHaveBean(WebProbes.MpProbe.class).doesNotHaveBean(WebProbes.BizProbe.class);
                    assertThat(routes(context.getBean(RequestMappingHandlerMapping.class)))
                            .containsExactlyInAnyOrder("/ops/food-test-probe", "/mp/retail-test-probe");
                });
    }

    @Test
    void noClientProfileRegistersNoFoodWeb() {
        runner.withPropertyValues("shop.food.enabled=true").run(context -> {
            assertThat(context).hasNotFailed().doesNotHaveBean(FoodWebAutoConfiguration.class);
            assertThat(routes(context.getBean(RequestMappingHandlerMapping.class)))
                    .containsExactly("/mp/retail-test-probe");
        });
    }

    @Test
    void combinedProfilesExposeEachRouteOnce() {
        runner.withPropertyValues("shop.food.enabled=true", "spring.profiles.active=api,ops")
                .run(context -> assertThat(routes(context.getBean(RequestMappingHandlerMapping.class)))
                        .containsExactlyInAnyOrder("/mp/food-test-probe", "/biz/food-test-probe",
                                "/ops/food-test-probe", "/mp/retail-test-probe"));
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void sharedConsumerAuthenticationStillApplies(boolean enabled) {
        runner.withUserConfiguration(HostSecurity.class)
                .withPropertyValues("shop.food.enabled=" + enabled, "spring.profiles.active=api")
                .run(context -> {
                    assertThat(context).hasNotFailed().hasSingleBean(SecurityFilterChain.class);
                    MemoryTokenStore store = context.getBean(MemoryTokenStore.class);
                    String token = store.issueConsumer("test-consumer");
                    String otherRealm = store.issue(TokenStore.SessionData.of(LoginUser.merchant("test-staff", "staff")));
                    var mvc = MockMvcBuilders.webAppContextSetup(context)
                            .addFilters(context.getBean(FilterChainProxy.class)).build();
                    mvc.perform(get("/mp/retail-test-probe")).andExpect(status().isUnauthorized());
                    mvc.perform(get("/mp/retail-test-probe").header("Authorization", "Bearer " + token))
                            .andExpect(status().isOk()).andExpect(content().string("retail"));
                    if (enabled) {
                        mvc.perform(get("/mp/food-test-probe")).andExpect(status().isUnauthorized());
                        mvc.perform(get("/mp/food-test-probe").header("Authorization", "Bearer " + otherRealm))
                                .andExpect(status().isUnauthorized());
                        mvc.perform(get("/mp/food-test-probe").header("Authorization", "Bearer " + token))
                                .andExpect(status().isOk()).andExpect(content().string("food-mp"));
                    }
                });
    }

    private static Set<String> routes(RequestMappingHandlerMapping mapping) {
        return mapping.getHandlerMethods().keySet().stream()
                .flatMap(info -> info.getPatternValues().stream()).collect(Collectors.toSet());
    }

    @Configuration(proxyBeanMethods = false)
    @EnableWebMvc
    @ComponentScan(basePackageClasses = WebProbes.class)
    static class HostScan {
    }

    /** 复用平台真实过滤器；仅测试宿主负责这条链，餐饮没有新增链。 */
    @Configuration(proxyBeanMethods = false)
    @EnableWebSecurity
    static class HostSecurity {
        @Bean
        MemoryTokenStore tokenStore() {
            return new MemoryTokenStore(Duration.ofMinutes(5));
        }

        @Bean
        SecurityFilterChain platformChain(HttpSecurity http, MemoryTokenStore tokenStore) throws Exception {
            return http.authorizeHttpRequests(auth -> auth.anyRequest().authenticated())
                    .exceptionHandling(errors -> errors.authenticationEntryPoint(
                            (request, response, exception) -> response.sendError(401)))
                    .addFilterBefore(new ConsumerTokenAuthFilter(tokenStore), UsernamePasswordAuthenticationFilter.class)
                    .build();
        }
    }
}
