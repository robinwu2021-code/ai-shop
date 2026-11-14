package ai.neargo.shop.scenario;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import org.springframework.http.MediaType;
import ai.neargo.shop.common.BizException;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import ai.neargo.shop.auth.BizContext;
import ai.neargo.shop.auth.BizContextFilter;
import ai.neargo.shop.auth.LivePermResolver;
import ai.neargo.shop.auth.LoginUser;
import ai.neargo.shop.auth.MerchantTokenAuthFilter;
import ai.neargo.shop.auth.PermChecker;
import ai.neargo.shop.auth.TokenStore;
import ai.neargo.shop.auth.store.MemoryTokenStore;
import ai.neargo.shop.merchant.service.ScheduleService;
import ai.neargo.shop.spi.user.StoreDirectoryPort;
import java.lang.reflect.Proxy;
import java.util.Arrays;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.web.FilterChainProxy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;

/** Real permission and scope filters guard maintenance; controlled service assertions isolate HTTP trust boundaries. */
class ScheduleControllerTest {
    private static final String QUERY = "/biz/schedules?resourceNos=R&from=2026-10-22T00:00:00Z"
            + "&to=2026-10-23T00:00:00Z";
    private String last;
    private WebApplicationContextRunner runner() {
        Class<?> controller = assertDoesNotThrow(() ->
                Class.forName("ai.neargo.shop.merchant.api.biz.BizScheduleController"));
        var service = (ScheduleService) Proxy.newProxyInstance(getClass().getClassLoader(),
                new Class<?>[]{ScheduleService.class}, (proxy, method, args) -> {
                    last = method.getName() + ":" + Arrays.toString(args);
                    return null;
                });
        return new WebApplicationContextRunner().withUserConfiguration(Host.class, controller)
                .withBean(ScheduleService.class, () -> service).withPropertyValues("spring.profiles.active=api");
    }
    @Test
    void authenticatedOwnerReadsTrustedCurrentStoreWhileAnonymousAndManagerAreRejected() {
        runner().run(context -> {
            var mvc = MockMvcBuilders.webAppContextSetup(context)
                    .addFilters(context.getBean(FilterChainProxy.class)).build();
            var tokens = context.getBean(MemoryTokenStore.class);
            String owner = tokens.issue(TokenStore.SessionData.of(LoginUser.merchant("OWNER", "owner")));
            String manager = tokens.issue(TokenStore.SessionData.of(LoginUser.merchant("MANAGER", "manager")));
            mvc.perform(get(QUERY)).andExpect(status().isUnauthorized());
            mvc.perform(get(QUERY).header("Authorization", "Bearer " + manager))
                    .andExpect(status().isForbidden());
            mvc.perform(get(QUERY).header("Authorization", "Bearer " + owner)
                    .header("X-Actor-No", "spoof")).andExpect(status().isOk());
            assertThat(last).contains("listSchedules", "E", "A", "OWNER").doesNotContain("spoof");
            mvc.perform(get(QUERY).header("Authorization", "Bearer " + owner)
                    .header("X-Store-No", "FOREIGN")).andExpect(status().isForbidden());
        });
    }
    @Test
    void commandsRequireDurableKeyAndNeverUseBodyActor() {
        runner().run(context -> {
            var mvc = MockMvcBuilders.webAppContextSetup(context)
                    .addFilters(context.getBean(FilterChainProxy.class)).build();
            String token = context.getBean(MemoryTokenStore.class).issue(
                    TokenStore.SessionData.of(LoginUser.merchant("OWNER", "owner")));
            String body = "{\"expectedRevision\":1,\"resourceNo\":\"R\",\"timezone\":\"UTC\","
                    + "\"weekdayMask\":127,\"startMinute\":600,\"endMinute\":660,\"endDayOffset\":0,"
                    + "\"validFrom\":\"2026-10-22\",\"validTo\":\"2026-10-23\","
                    + "\"status\":\"ACTIVE\",\"reason\":\"排班\",\"actorNo\":\"spoof\"}";
            mvc.perform(post("/biz/schedule-rules").header("Authorization", "Bearer " + token)
                    .contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isBadRequest());
            mvc.perform(post("/biz/schedule-rules").header("Authorization", "Bearer " + token)
                    .header("Idempotency-Key", "K").contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isOk());
            assertThat(last).contains("saveScheduleRule", "OWNER", "K").doesNotContain("spoof");
        });
    }
    @RestControllerAdvice
    static class Errors {
        @ExceptionHandler(BizException.class)
        ResponseEntity<Void> forbidden(BizException error) { return ResponseEntity.status(403).build(); }
    }

    @Configuration(proxyBeanMethods = false)
    @EnableWebMvc
    @EnableWebSecurity
    @EnableMethodSecurity
    static class Host {
        @Bean
        MemoryTokenStore tokens() { return new MemoryTokenStore(Duration.ofMinutes(5)); }
        @Bean("perm")
        PermChecker perm(ObjectProvider<LivePermResolver> resolver) { return new PermChecker(resolver); }
        @Bean
        Errors errors() { return new Errors(); }
        @Bean
        StoreDirectoryPort stores() {
            var stores = mock(StoreDirectoryPort.class);
            when(stores.cards(List.of("A"))).thenReturn(Map.of("A", new StoreDirectoryPort.StoreCard(
                    "A", "店", "E", null, "ACTIVE", true, null, null, null, null, 0, 0)));
            return stores;
        }
        @Bean
        SecurityFilterChain chain(HttpSecurity http, MemoryTokenStore tokens) throws Exception {
            var context = new BizContextFilter((actor, store) -> new BizContext("E", Set.of(), Set.of(),
                    Set.of("A"), "A", "OWNER".equals(actor), Map.of(), Map.of("A", Set.of("biz:store"))));
            return http.csrf(csrf -> csrf.disable())
                    .authorizeHttpRequests(auth ->
                            auth.requestMatchers("/mp/**").permitAll().anyRequest().authenticated())
                    .exceptionHandling(errors -> errors.authenticationEntryPoint((req, res, ex) -> res.sendError(401)))
                    .addFilterBefore(new MerchantTokenAuthFilter(tokens), UsernamePasswordAuthenticationFilter.class)
                    .addFilterAfter(context, MerchantTokenAuthFilter.class).build();
        }
    }
}
