package ai.neargo.shop.food.scenario;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;

import ai.neargo.shop.auth.ConsumerTokenAuthFilter;
import ai.neargo.shop.auth.LoginUser;
import ai.neargo.shop.auth.TokenStore;
import ai.neargo.shop.auth.store.MemoryTokenStore;
import ai.neargo.shop.food.config.FoodWebAutoConfiguration;
import ai.neargo.shop.food.dto.CatalogQuery;
import ai.neargo.shop.food.dto.CatalogVO;
import ai.neargo.shop.food.service.CatalogService;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.FilterChainProxy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

class CatalogApiTest {
    private final WebApplicationContextRunner runner = new WebApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(FoodWebAutoConfiguration.class))
            .withUserConfiguration(FoodPolicyApiTest.PolicyHost.class, DishApiTest.Security.class, Host.class)
            .withPropertyValues("shop.food.enabled=true", "spring.profiles.active=api");
    @Test void staffUsesOriginalGoodsPermissionAndActualStoreAndCannotPreviewFuture() {
        runner.run(c -> {
            var mvc = mvc(c); var staff = token(c, LoginUser.merchant("staff", "Staff"));
            String route = "/biz/x/food/stores/A/catalog";
            mvc.perform(get(route).header("Authorization", staff).param("orderSource",
                    "POS").param("fulfillment", "DINE_IN"))
                    .andExpect(jsonPath("$.data.storeNo").value("A"));
            mvc.perform(get(route).header("Authorization", token(c, LoginUser.merchant("cashier", "Cashier")))
                    .param("orderSource", "POS").param("fulfillment", "DINE_IN"))
                    .andExpect(jsonPath("$.code").value(70006));
            mvc.perform(get(route.replace("/A/", "/B/")).header("Authorization", staff)
                    .param("orderSource", "POS").param("fulfillment", "DINE_IN"))
                    .andExpect(jsonPath("$.code").value(10403));
            for (String forbidden : List.of("entityNo", "at")) {
                mvc.perform(get(route).header("Authorization", staff).param("orderSource", "POS")
                        .param("fulfillment", "DINE_IN").param(forbidden, "OTHER"))
                        .andExpect(jsonPath("$.code").value(10400));
            }
            assertThat(c.getBean(AtomicInteger.class)).hasValue(1);
        });
    }
    @Test void publicH5AndOriginalQrGuestCannotSpoofStaffChannelBuyerOrFutureScope() {
        runner.run(c -> {
            var mvc = mvc(c); String route = "/mp/x/food/catalog";
            mvc.perform(get(route).param("storeNo", "A").param("orderSource", "H5").param("fulfillment", "PICKUP"))
                    .andExpect(jsonPath("$.data.orderSource").value("H5"));
            mvc.perform(get(route).header("Authorization", token(c, LoginUser.consumer("BUYER", "Buyer")))
                    .header("X-Food-Session", "secret").param("storeNo", "A").param("sessionNo", "SESSION")
                    .param("orderSource", "QR").param("fulfillment", "DINE_IN"))
                    .andExpect(jsonPath("$.data.orderSource").value("QR"));
            for (String source : List.of("POS", "WAITER", "KIOSK", "APP", "CHANNEL", "QR")) {
                mvc.perform(get(route).param("storeNo", "A").param("orderSource",
                        source).param("fulfillment", "DINE_IN"))
                        .andExpect(jsonPath("$.code").value(10400));
            }
            for (String field : List.of("entityNo", "buyerNo", "at", "channelBindingNo")) {
                mvc.perform(get(route).param("storeNo", "A").param("orderSource", "H5").param("fulfillment", "PICKUP")
                        .param(field, "OTHER")).andExpect(jsonPath("$.code").value(10400));
            }
            assertThat(c.getBean(AtomicInteger.class)).hasValue(2);
        });
    }
    private MockMvc mvc(WebApplicationContext c) {
        return MockMvcBuilders.webAppContextSetup(c).addFilters(c.getBean(FilterChainProxy.class)).build();
    }
    private String token(WebApplicationContext c, LoginUser user) {
        return "Bearer " + c.getBean(MemoryTokenStore.class).issue(TokenStore.SessionData.of(user));
    }
    @Configuration(proxyBeanMethods=false)
    static class Host {
        @Bean @Order(-1) SecurityFilterChain consumer(HttpSecurity http, MemoryTokenStore tokens) throws Exception {
            return http.securityMatcher("/mp/**").csrf(s -> s.disable()).authorizeHttpRequests(s ->
                    s.anyRequest().permitAll())
                    .addFilterBefore(new ConsumerTokenAuthFilter(tokens),
                            UsernamePasswordAuthenticationFilter.class).build();
        }
        @Bean AtomicInteger catalogCalls() { return new AtomicInteger(); }
        @Bean CatalogService catalog(AtomicInteger calls) {
            return new CatalogService() {
                private CatalogVO view(String store, String source, String fulfillment) {
                    calls.incrementAndGet();
                    return new CatalogVO(store, source, fulfillment, Instant.parse("2026-10-21T12:00:00Z"),
                            List.of(), List.of());
                }
                @Override public CatalogVO read(String entity, String store, CatalogQuery query) {
                    assertThat(entity).isEqualTo("E"); assertThat(store).isEqualTo("A");
                    return view(store, query.orderSource(), query.fulfillment());
                }
                @Override public CatalogVO publicCatalog(String store, String fulfillment) { return
                        view(store, "H5", fulfillment); }
                @Override public CatalogVO guest(String store, String session, String access, String buyer) {
                    assertThat(store).isEqualTo("A"); assertThat(session).isEqualTo("SESSION");
                    assertThat(access).isEqualTo("secret"); assertThat(buyer).isEqualTo("BUYER");
                    return view(store, "QR", "DINE_IN");
                }
            };
        }
    }
}
