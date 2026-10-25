package ai.neargo.shop.food.scenario;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import ai.neargo.shop.auth.BizContext;
import ai.neargo.shop.auth.BizContextFilter;
import ai.neargo.shop.auth.LoginUser;
import ai.neargo.shop.auth.MerchantTokenAuthFilter;
import ai.neargo.shop.auth.TokenStore;
import ai.neargo.shop.auth.store.MemoryTokenStore;
import ai.neargo.shop.food.config.FoodWebAutoConfiguration;
import ai.neargo.shop.food.dto.KitchenContracts.TaskVO;
import ai.neargo.shop.food.service.KitchenTaskService;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.FilterChainProxy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

/** Active queue contract: unsupported terminal filters must not masquerade as an empty history. */
class KitchenQueueApiTest {
    private static final String PATH = "/biz/x/food/stores/A/kitchen/tasks";
    private final WebApplicationContextRunner runner = new WebApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(FoodWebAutoConfiguration.class))
            .withUserConfiguration(FoodPolicyApiTest.PolicyHost.class, Security.class, Host.class)
            .withPropertyValues("shop.food.enabled=true", "spring.profiles.active=api");

    @ParameterizedTest
    @ValueSource(strings = {"SERVED", "VOIDED"})
    void terminalFiltersAreExplicitlyRejectedBeforeReadingActiveQueue(String terminal) {
        runner.run(c -> {
            mvc(c).perform(get(PATH).header("Authorization", token(c, "staff"))
                    .param("stationNo", "HOT").param("status", terminal))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.code").value(10400));
            assertThat(c.getBean(Calls.class).reads).hasValue(0);
        });
    }

    @Test void supportedStatusAndPaginationKeepTheTrustedScopeAndRealResultCount() {
        runner.run(c -> {
            var mvc = mvc(c); var auth = token(c, "staff");
            mvc.perform(get(PATH).header("Authorization", auth).param("stationNo", "HOT")
                    .param("page", "2").param("size", "1"))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.code").value(0))
                    .andExpect(jsonPath("$.data.total").value(3))
                    .andExpect(jsonPath("$.data.records[0].taskNo").value("READY1"));
            mvc.perform(get(PATH).header("Authorization", auth).param("stationNo", "HOT").param("status", "READY"))
                    .andExpect(jsonPath("$.code").value(0)).andExpect(jsonPath("$.data.total").value(1))
                    .andExpect(jsonPath("$.data.records[0].taskNo").value("READY1"));
        });
    }

    @Test void permissionScopeAndUnknownOrRepeatedParametersAreRejectedWithoutReadingTasks() {
        runner.run(c -> {
            var mvc = mvc(c); var auth = token(c, "staff");
            mvc.perform(get(PATH).header("Authorization", token(c, "cashier")).param("stationNo", "HOT"))
                    .andExpect(jsonPath("$.code").value(70006));
            mvc.perform(get("/biz/x/food/stores/B/kitchen/tasks").header("Authorization", auth).param("stationNo", "HOT"))
                    .andExpect(jsonPath("$.code").value(10403));
            mvc.perform(get(PATH).header("Authorization", auth).param("stationNo", "HOT").param("entityNo", "FOREIGN"))
                    .andExpect(jsonPath("$.code").value(10400));
            mvc.perform(get(PATH).header("Authorization", auth).param("stationNo", "HOT").param("status", "READY", "HELD"))
                    .andExpect(jsonPath("$.code").value(10400));
            mvc.perform(get(PATH).header("Authorization", auth).param("stationNo", "HOT").param("size", "101"))
                    .andExpect(jsonPath("$.code").value(10400));
            assertThat(c.getBean(Calls.class).reads).hasValue(0);
        });
    }

    private static String token(WebApplicationContext c, String actor) {
        return "Bearer " + c.getBean(MemoryTokenStore.class)
                .issue(TokenStore.SessionData.of(LoginUser.merchant(actor, actor)));
    }
    private static MockMvc mvc(WebApplicationContext c) {
        return MockMvcBuilders.webAppContextSetup(c).addFilters(c.getBean(FilterChainProxy.class)).build();
    }
    private static TaskVO task(String no, String state) {
        return new TaskVO(no, "TICKET", "BATCH", "LINE", null, "HOT", "MAKE", 1L,
                BigDecimal.ONE, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
                state, null, null, 0L, "MAKE", null);
    }
    static class Calls { final AtomicInteger reads = new AtomicInteger(); }
    @Configuration(proxyBeanMethods = false)
    static class Host {
        @Bean Calls queueCalls() { return new Calls(); }
        @Bean tools.jackson.databind.ObjectMapper kitchenMapper() { return new tools.jackson.databind.ObjectMapper(); }
        @Bean KitchenTaskService kitchenTaskService(Calls calls) {
            // Service persistence is tested separately in KitchenQueueMapperTest; this exercises the HTTP boundary.
            var service = mock(KitchenTaskService.class);
            when(service.queue(any(), any(), any(), any())).thenAnswer(invocation -> {
                assertThat((String) invocation.getArgument(0)).isEqualTo("E");
                assertThat((String) invocation.getArgument(1)).isEqualTo("A");
                assertThat((String) invocation.getArgument(2)).isEqualTo("HOT");
                assertThat((String) invocation.getArgument(3)).isNull();
                calls.reads.incrementAndGet();
                return List.of(task("HELD1", "HELD"), task("READY1", "READY"), task("MAKING1", "MAKING"));
            });
            return service;
        }
    }
    @Configuration(proxyBeanMethods = false)
    static class Security extends FoodPolicyApiTest.PolicySecurity {
        @Override @Bean
        SecurityFilterChain chain(HttpSecurity http, MemoryTokenStore tokens) throws Exception {
            var context = new BizContextFilter((actor, requested) -> new BizContext("E", Set.of(), Set.of(),
                    Set.of("A", "B"), "A", false, Map.of(), Map.of(
                            "A", "cashier".equals(actor) ? Set.of() : Set.of("biz:receive"), "B", Set.of("biz:receive"))));
            return http.csrf(csrf -> csrf.disable()).authorizeHttpRequests(auth -> auth.anyRequest().authenticated())
                    .addFilterBefore(new MerchantTokenAuthFilter(tokens), UsernamePasswordAuthenticationFilter.class)
                    .addFilterAfter(context, MerchantTokenAuthFilter.class).build();
        }
    }
}
