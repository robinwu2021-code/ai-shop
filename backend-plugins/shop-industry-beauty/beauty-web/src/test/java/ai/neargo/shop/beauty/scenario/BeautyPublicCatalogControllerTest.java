package ai.neargo.shop.beauty.scenario;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import ai.neargo.shop.auth.BizContext;
import ai.neargo.shop.auth.BizContextFilter;
import ai.neargo.shop.auth.LivePermResolver;
import ai.neargo.shop.auth.LoginUser;
import ai.neargo.shop.auth.MerchantTokenAuthFilter;
import ai.neargo.shop.auth.PermChecker;
import ai.neargo.shop.auth.TokenStore;
import ai.neargo.shop.auth.store.MemoryTokenStore;
import ai.neargo.shop.beauty.dto.PublicStoreVO;
import ai.neargo.shop.beauty.service.ServiceCatalogQueryService;
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

/** Real permission/scope filters protect business reads while public routes remain read-only and anonymous. */
class BeautyPublicCatalogControllerTest {
    private String last;
    private WebApplicationContextRunner runner() {
        assertDoesNotThrow(() -> Class.forName("ai.neargo.shop.beauty.api.mp.MpStoreController"));
        assertDoesNotThrow(() -> Class.forName("ai.neargo.shop.beauty.api.mp.MpServiceController"));
        assertDoesNotThrow(() -> Class.forName("ai.neargo.shop.beauty.api.biz.BizServiceController"));
        var service = (ServiceCatalogQueryService) Proxy.newProxyInstance(getClass().getClassLoader(),
                new Class<?>[]{ServiceCatalogQueryService.class}, (proxy, method, args) -> {
                    last = method.getName() + ":" + Arrays.toString(args);
                    if (method.getName().equals("getPublicStore")) {
                        return new PublicStoreVO("A", "店", null, "ACTIVE", null, null, "PV", null, null, null);
                    }
                    return null;
                });
        return new WebApplicationContextRunner().withUserConfiguration(Host.class)
                .withConfiguration(AutoConfigurations.of(assertDoesNotThrow(() ->
                        Class.forName("ai.neargo.shop.beauty.config.BeautyWebAutoConfiguration"))))
                .withBean(ServiceCatalogQueryService.class, () -> service)
                .withPropertyValues("shop.beauty.enabled=true", "shop.beauty.deployment=embedded",
                        "spring.profiles.active=api");
    }

    @Test
    void anonymousPublicRoutesDoNotRequireOrAcceptMerchantScope() {
        runner().run(context -> {
            var mvc = MockMvcBuilders.webAppContextSetup(context)
                    .addFilters(context.getBean(FilterChainProxy.class)).build();
            mvc.perform(get("/mp/x/beauty/stores/A").header("X-Entity-No", "spoof"))
                    .andExpect(status().isOk());
            assertThat(last).isEqualTo("getPublicStore:[A]");
            mvc.perform(get("/mp/x/beauty/services/SKU").param("storeNo", "A"))
                    .andExpect(status().isOk());
            assertThat(last).contains("getPublicService", "SKU", "storeNo=A");
            mvc.perform(get("/mp/x/beauty/services/SKU")).andExpect(status().isBadRequest());
        });
    }

    @Test
    void businessReadUsesAuthenticatedActorAndRejectsRawForeignStore() {
        runner().run(context -> {
            var mvc = MockMvcBuilders.webAppContextSetup(context)
                    .addFilters(context.getBean(FilterChainProxy.class)).build();
            String token = context.getBean(MemoryTokenStore.class).issue(
                    TokenStore.SessionData.of(LoginUser.merchant("staff", "staff")));
            mvc.perform(get("/biz/x/beauty/services/SKU").param("storeNo", "A")
                    .header("Authorization", "Bearer " + token).header("X-Actor-No", "spoof"))
                    .andExpect(status().isOk());
            assertThat(last).contains("getService", "E", "A", "staff").doesNotContain("spoof");
            mvc.perform(get("/biz/x/beauty/services/SKU").param("storeNo", "A")
                    .header("Authorization", "Bearer " + token).header("X-Store-No", "FOREIGN"))
                    .andExpect(status().isForbidden());
            mvc.perform(get("/biz/x/beauty/services/SKU").param("storeNo", "A"))
                    .andExpect(status().isUnauthorized());
        });
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
        BeautyPolicyControllerTest.Errors errors() { return new BeautyPolicyControllerTest.Errors(); }
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
                    Set.of("A"), "A", false, Map.of(), Map.of("A", Set.of("biz:store"))));
            return http.csrf(csrf -> csrf.disable())
                    .authorizeHttpRequests(auth ->
                            auth.requestMatchers("/mp/**").permitAll().anyRequest().authenticated())
                    .exceptionHandling(errors -> errors.authenticationEntryPoint((req, res, ex) -> res.sendError(401)))
                    .addFilterBefore(new MerchantTokenAuthFilter(tokens), UsernamePasswordAuthenticationFilter.class)
                    .addFilterAfter(context, MerchantTokenAuthFilter.class).build();
        }
    }
}
