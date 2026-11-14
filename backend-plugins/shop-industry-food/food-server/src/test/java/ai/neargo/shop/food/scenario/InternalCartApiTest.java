package ai.neargo.shop.food.scenario;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import ai.neargo.shop.food.dto.CartResultVO;
import ai.neargo.shop.food.dto.CartVO;
import ai.neargo.shop.food.server.api.InternalCartController;
import ai.neargo.shop.food.server.config.FoodInternalSecurityConfiguration;
import ai.neargo.shop.food.service.CheckCartService;
import ai.neargo.shop.food.spi.CartInternalApi;
import ai.neargo.shop.svc.InternalHttp;
import java.lang.reflect.Proxy;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.web.FilterChainProxy;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;

class InternalCartApiTest {
    final WebApplicationContextRunner runner = new WebApplicationContextRunner().withUserConfiguration(Host.class)
            .withPropertyValues("shop.food.enabled=true", "shop.services.internal-token=wire", ai.neargo.shop.food.scope.FoodScopeTestSigning.KEY_PROPERTY);

    @Test void serviceTokenPrecedesStrictParsingAndReceiptIsUnwrapped() {
        runner.run(c -> {
            var mvc = MockMvcBuilders.webAppContextSetup(c).addFilters(c.getBean(FilterChainProxy.class)).build();
            String body = "{\"entityNo\":\"E\",\"storeNo\":\"A\",\"cartNo\":\"C\"}";
            mvc.perform(post(CartInternalApi.GET_PATH).contentType(MediaType.APPLICATION_JSON).content("{"))
                    .andExpect(status().isUnauthorized());
            mvc.perform(post(CartInternalApi.GET_PATH).header(InternalHttp.TOKEN_HEADER, "wire").with(ai.neargo.shop.food.scope.FoodScopeTestSigning.system())
                    .contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.cart.cartNo").value("C"))
                    .andExpect(jsonPath("$.code").doesNotExist());
            for (String invalid : List.of(body.replace("\"C\"", "12"),
                    body.replace("\"C\"", "{\"value\":\"C\"}"),
                    body.replace("\"C\"", "\"C\",\"buyerNo\":\"forged\""))) {
                mvc.perform(post(CartInternalApi.GET_PATH).header(InternalHttp.TOKEN_HEADER, "wire").with(ai.neargo.shop.food.scope.FoodScopeTestSigning.system())
                        .contentType(MediaType.APPLICATION_JSON).content(invalid)).andExpect(status().isBadRequest());
            }
            assertThat(c.getBean(AtomicInteger.class)).hasValue(1);
        });
    }

    @Configuration(proxyBeanMethods = false)
    @EnableWebMvc
    @Import({FoodInternalSecurityConfiguration.class, InternalCartController.class})
    static class Host {
        @Bean AtomicInteger cartCalls() { return new AtomicInteger(); }
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
                        assertThat(a).containsExactly("E", "A", "C"); calls.incrementAndGet();
                        return new CartResultVO(new CartVO("C", "A", "CHECK", null, null, "OPEN", "POS",
                                "DINE_IN", null, 0L, List.of()));
                    });
        }
    }
}
