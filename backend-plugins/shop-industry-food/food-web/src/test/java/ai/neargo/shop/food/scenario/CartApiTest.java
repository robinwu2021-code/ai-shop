package ai.neargo.shop.food.scenario;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;

import ai.neargo.shop.auth.ConsumerTokenAuthFilter;
import ai.neargo.shop.auth.LoginUser;
import ai.neargo.shop.auth.TokenStore;
import ai.neargo.shop.auth.store.MemoryTokenStore;
import ai.neargo.shop.food.api.biz.BizCartController;
import ai.neargo.shop.food.api.mp.MpCartController;
import ai.neargo.shop.food.config.FoodWebAutoConfiguration;
import ai.neargo.shop.food.dto.CartAccess;
import ai.neargo.shop.food.dto.CartResultVO;
import ai.neargo.shop.food.dto.CartVO;
import ai.neargo.shop.food.service.CheckCartService;
import java.lang.reflect.Proxy;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.core.annotation.Order;
import org.springframework.http.MediaType;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.FilterChainProxy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class CartApiTest {
    final WebApplicationContextRunner runner = new WebApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(FoodWebAutoConfiguration.class))
            .withUserConfiguration(FoodPolicyApiTest.PolicyHost.class, CheckApiTest.Security.class, Host.class)
            .withPropertyValues("shop.food.enabled=true", "spring.profiles.active=api");

    @Test void staffReadsResolveTrustedScopeAndRequireExistingPermission() {
        runner.run(c -> {
            var mvc = MockMvcBuilders.webAppContextSetup(c).addFilters(c.getBean(FilterChainProxy.class)).build();
            var tokens = c.getBean(MemoryTokenStore.class);
            String staff = "Bearer " + tokens.issue(TokenStore.SessionData.of(LoginUser.merchant("staff", "Staff")));
            String denied = "Bearer " + tokens.issue(TokenStore.SessionData.of(LoginUser.merchant("denied", "Denied")));
            mvc.perform(get("/biz/x/food/carts/C").header("Authorization", staff))
                    .andExpect(jsonPath("$.data.cart.storeNo").value("A"));
            mvc.perform(get("/biz/x/food/carts/C").header("Authorization", denied))
                    .andExpect(jsonPath("$.code").value(70006));
            assertThat(c.getBean(AtomicInteger.class)).hasValue(1);
        });
    }

    @Test void customerBuyerComesFromConsumerLoginAndCredentialsRemainOptionalForH5() {
        runner.run(c -> {
            var mvc = MockMvcBuilders.webAppContextSetup(c).addFilters(c.getBean(FilterChainProxy.class)).build();
            String buyer = "Bearer " + c.getBean(MemoryTokenStore.class)
                    .issue(TokenStore.SessionData.of(LoginUser.consumer("BUYER", "Buyer")));
            mvc.perform(post("/mp/x/food/stores/A/carts").header("Authorization", buyer)
                    .header("Idempotency-Key", "original").contentType(MediaType.APPLICATION_JSON)
                    .content("{\"fulfillment\":\"PICKUP\"}"))
                    .andExpect(jsonPath("$.data.cart.cartNo").value("C"));
            mvc.perform(get("/mp/x/food/carts/C").header("Authorization", buyer)
                    .header("X-Food-Session", "secret").param("sessionNo", "G"))
                    .andExpect(jsonPath("$.data.cart.cartNo").value("C"));
            for (String field : List.of("buyerNo", "orderSource")) {
                mvc.perform(post("/mp/x/food/stores/A/carts").header("Authorization", buyer)
                        .header("Idempotency-Key", "original").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"fulfillment\":\"PICKUP\",\"" + field + "\":\"FORGED\"}"))
                        .andExpect(jsonPath("$.code").value(10400));
                mvc.perform(get("/mp/x/food/carts/C").header("Authorization", buyer).param(field, "FORGED"))
                        .andExpect(jsonPath("$.code").value(10400));
            }
            assertThat(c.getBean(AtomicInteger.class)).hasValue(2);
        });
    }

    @Configuration(proxyBeanMethods = false)
    @Import({BizCartController.class, MpCartController.class})
    static class Host {
        @Bean AtomicInteger cartCalls() { return new AtomicInteger(); }
        @Bean @Order(-1) SecurityFilterChain consumer(HttpSecurity http, MemoryTokenStore tokens) throws Exception {
            return http.securityMatcher("/mp/**").csrf(s -> s.disable()).authorizeHttpRequests(s ->
                    s.anyRequest().permitAll()).addFilterBefore(new ConsumerTokenAuthFilter(tokens),
                    UsernamePasswordAuthenticationFilter.class).build();
        }
        @Bean CheckCartService carts(AtomicInteger calls) {
            return (CheckCartService) Proxy.newProxyInstance(CheckCartService.class.getClassLoader(),
                    new Class<?>[]{CheckCartService.class}, (p, m, a) -> {
                        if (m.getDeclaringClass() == Object.class) {
                            return switch (m.getName()) {
                                case "hashCode" -> System.identityHashCode(p);
                                case "equals" -> p == a[0];
                                default -> "cart-fixture";
                            };
                        }
                        if (m.getName().equals("getCart")) {
                            assertThat(a[0]).isEqualTo("E"); assertThat(a[1]).isEqualTo("A");
                        } else {
                            var access = (CartAccess) a[1];
                            assertThat(access.buyerNo()).isEqualTo("BUYER");
                            assertThat(access.sessionNo())
                                    .isEqualTo(m.getName().equals("customerGetCart") ? "G" : null);
                            assertThat(access.accessToken()).isEqualTo(m.getName().equals("customerGetCart")
                                    ? "secret" : null);
                        }
                        calls.incrementAndGet();
                        return new CartResultVO(new CartVO("C", "A", null, "G", null, "OPEN", "H5", "PICKUP",
                                null, 0L, List.of()));
                    });
        }
    }
}
