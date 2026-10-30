package ai.neargo.shop.beauty.scenario;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import ai.neargo.shop.auth.BizContext;
import ai.neargo.shop.auth.BizContextFilter;
import ai.neargo.shop.auth.LoginUser;
import ai.neargo.shop.auth.LivePermResolver;
import ai.neargo.shop.auth.MerchantTokenAuthFilter;
import ai.neargo.shop.auth.PermChecker;
import ai.neargo.shop.auth.TokenStore;
import ai.neargo.shop.auth.store.MemoryTokenStore;
import ai.neargo.shop.common.BizException;
import ai.neargo.shop.spi.user.StoreDirectoryPort;
import java.lang.reflect.Proxy;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.web.FilterChainProxy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;

/** Actual controller and authentication chain; the web-only artifact uses the declared Service boundary. */
class BeautyPolicyControllerTest {
    private static final String PATH = "/biz/x/beauty/store-policies/versions";
    private static final String BODY = "{\"expectedRevision\":0,\"validFrom\":\"2026-10-20T10:00:00Z\","
            + "\"policy\":{\"bookingAheadDays\":7,\"cancelRule\":{\"freeCancelBeforeMinutes\":60,"
            + "\"allowCustomerCancel\":true},\"allowedModes\":[\"PREPAID_SINGLE\"]}}";

    private Class<?> required(String name) {
        return assertDoesNotThrow(() -> Class.forName("ai.neargo.shop.beauty." + name));
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private WebApplicationContextRunner runner() {
        required("api.biz.BizStorePolicyController");
        Class service = required("service.StorePolicyService");
        Object stub = Proxy.newProxyInstance(service.getClassLoader(), new Class<?>[]{service},
                (proxy, method, args) -> {
            if (method.getName().equals("toString")) { return "policy-contract"; }
            return required("dto.PolicyVersionVO")
                    .getConstructor(String.class, String.class, long.class, OffsetDateTime.class, String.class)
                    .newInstance(args[0] + ":" + args[1] + ":" + args[2], "V", 1L,
                            OffsetDateTime.parse("2026-10-20T10:00:00Z"), "a".repeat(64));
        });
        return new WebApplicationContextRunner().withUserConfiguration(Host.class)
                .withConfiguration(AutoConfigurations.of(required("config.BeautyWebAutoConfiguration")))
                .withBean("policyService", service, () -> stub)
                .withPropertyValues("shop.beauty.enabled=true", "shop.beauty.deployment=embedded",
                        "spring.profiles.active=api");
    }

    private String token(ApplicationContext context, String actor) {
        return context.getBean(MemoryTokenStore.class)
                .issue(TokenStore.SessionData.of(LoginUser.merchant(actor, actor)));
    }

    private MockMvc mvc(WebApplicationContext context) {
        return MockMvcBuilders.webAppContextSetup(context).addFilters(context.getBean(FilterChainProxy.class)).build();
    }

    @Test
    void usesAuthenticatedActorAndResolvedStoreFacts() {
        runner().run(context -> mvc(context).perform(post(PATH)
                .header("Authorization", "Bearer " + token(context, "staff"))
                .header("Idempotency-Key", "K").contentType("application/json").content(BODY))
                .andExpect(status().isOk()).andExpect(jsonPath("$.policyNo").value("E:A:staff")));
    }

    @Test
    void rejectsInvalidHeaderAfterRetailDefaultFallback() {
        runner().run(context -> mvc(context).perform(post(PATH)
                .header("Authorization", "Bearer " + token(context, "staff"))
                .header(BizContextFilter.STORE_HEADER, "FOREIGN")
                .header("Idempotency-Key", "K").contentType("application/json").content(BODY))
                .andExpect(status().isForbidden()));
    }

    @Test
    void rejectsForeignDirectoryOwnerEvenForAnAuthorizedStore() {
        runner().run(context -> mvc(context).perform(post(PATH)
                .header("Authorization", "Bearer " + token(context, "staff"))
                .header(BizContextFilter.STORE_HEADER, "B")
                .header("Idempotency-Key", "K").contentType("application/json").content(BODY))
                .andExpect(status().isForbidden()));
    }

    @Test
    void deniesPolicyActionWithoutCurrentStorePermission() {
        runner().run(context -> mvc(context).perform(post(PATH)
                .header("Authorization", "Bearer " + token(context, "clerk"))
                .header("Idempotency-Key", "K").contentType("application/json").content(BODY))
                .andExpect(status().isForbidden()));
    }

    @Test
    void requiresLoginAndKey() {
        runner().run(context -> {
            mvc(context).perform(post(PATH).header("Idempotency-Key", "K")
                    .contentType("application/json").content(BODY)).andExpect(status().isUnauthorized());
            mvc(context).perform(post(PATH).header("Authorization", "Bearer " + token(context, "staff"))
                    .contentType("application/json").content(BODY)).andExpect(status().isBadRequest());
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
                    "A", "A", "E", null, "ACTIVE", true, null, null, null, null, 0, 0)));
            when(stores.cards(List.of("B"))).thenReturn(Map.of("B", new StoreDirectoryPort.StoreCard(
                    "B", "B", "FOREIGN", null, "ACTIVE", false, null, null, null, null, 0, 0)));
            return stores;
        }

        @Bean
        SecurityFilterChain chain(HttpSecurity http, MemoryTokenStore tokens) throws Exception {
            var context = new BizContextFilter((actor, store) -> new BizContext("E", Set.of(), Set.of(),
                    Set.of("A", "B"), "A", false, Map.of(), Map.of("A", actor.equals("clerk")
                            ? Set.of() : Set.of("biz:store"), "B", Set.of("biz:store"))));
            return http.csrf(csrf -> csrf.disable()).authorizeHttpRequests(auth -> auth.anyRequest().authenticated())
                    .exceptionHandling(errors -> errors.authenticationEntryPoint((req, res, ex) -> res.sendError(401)))
                    .addFilterBefore(new MerchantTokenAuthFilter(tokens), UsernamePasswordAuthenticationFilter.class)
                    .addFilterAfter(context, MerchantTokenAuthFilter.class).build();
        }
    }
}
