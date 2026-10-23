package ai.neargo.shop.food.scenario;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.food.dto.CartAccess;
import ai.neargo.shop.food.dto.DiningPriceFacts;
import ai.neargo.shop.food.server.api.InternalPricingSourceController;
import ai.neargo.shop.food.server.config.FoodInternalSecurityConfiguration;
import ai.neargo.shop.food.service.DiningPricingFactsService;
import ai.neargo.shop.food.spi.PricingSourceInternalApi;
import ai.neargo.shop.svc.InternalHttp;
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

class InternalPricingSourceApiTest {
    private final WebApplicationContextRunner runner = new WebApplicationContextRunner()
            .withUserConfiguration(Host.class).withPropertyValues("shop.food.enabled=true",
                    "shop.services.internal-token=wire");
    @Test void authenticationBeforeParsingAndStrictNoPriceCommandBoundary() {
        runner.run(c -> {
            var mvc = MockMvcBuilders.webAppContextSetup(c).addFilters(c.getBean(FilterChainProxy.class)).build();
            String body = "{\"entityNo\":\"E\",\"storeNo\":\"A\",\"cartNo\":\"C\",\"expectedVersion\":4}";
            mvc.perform(post(PricingSourceInternalApi.STAFF_PATH).contentType(MediaType.APPLICATION_JSON).content("{"))
                    .andExpect(status().isUnauthorized());
            for (String invalid : List.of(body.replace(",\"expectedVersion\":4", ""),
                    body.replace("\"expectedVersion\":4", "\"expectedVersion\":\"4\""),
                    body.replace("\"expectedVersion\":4", "\"expectedVersion\":4.5"),
                    body.replace("\"expectedVersion\":4", "\"expectedVersion\":null"),
                    body.replace("\"C\"", "12"),
                    body.replace("\"expectedVersion\":4", "\"expectedVersion\":4,\"price\":1"))) {
                mvc.perform(post(PricingSourceInternalApi.STAFF_PATH).header(InternalHttp.TOKEN_HEADER, "wire")
                        .contentType(MediaType.APPLICATION_JSON).content(invalid)).andExpect(status().isBadRequest());
            }
            mvc.perform(post(PricingSourceInternalApi.STAFF_PATH).header(InternalHttp.TOKEN_HEADER, "wire")
                    .contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isOk())
                    .andExpect(jsonPath("$.schemaVersion").value(1)).andExpect(jsonPath("$.code").doesNotExist());
            mvc.perform(post(PricingSourceInternalApi.CUSTOMER_PATH).header(InternalHttp.TOKEN_HEADER, "wire")
                    .contentType(MediaType.APPLICATION_JSON).content("{\"cartNo\":\"C\",\"expectedVersion\":4,"
                            + "\"access\":{\"sessionNo\":\"S\",\"accessToken\":\"secret\",\"buyerNo\":\"B\"}}"))
                    .andExpect(status().isForbidden());
            mvc.perform(post(PricingSourceInternalApi.STAFF_PATH).header(InternalHttp.TOKEN_HEADER, "wire")
                    .contentType(MediaType.APPLICATION_JSON).content(body.replace("\"C\"", "\"UNSUPPORTED\"")))
                    .andExpect(status().isServiceUnavailable());
            assertThat(c.getBean(AtomicInteger.class)).hasValue(3);
        });
    }
    @Configuration(proxyBeanMethods = false)
    @EnableWebMvc
    @Import({FoodInternalSecurityConfiguration.class, InternalPricingSourceController.class})
    static class Host {
        @Bean AtomicInteger calls() { return new AtomicInteger(); }
        @Bean DiningPricingFactsService facts(AtomicInteger calls) {
            return new DiningPricingFactsService() {
                @Override public DiningPriceFacts staff(String e, String s, String cart, long version) {
                    assertThat(e).isEqualTo("E"); assertThat(s).isEqualTo("A"); assertThat(version).isEqualTo(4);
                    calls.incrementAndGet();
                    if ("UNSUPPORTED".equals(cart)) throw BizException.of(ErrorCode.PRICING_OWNER_UNSUPPORTED);
                    return new DiningPriceFacts(1, null, "receipt");
                }
                @Override public DiningPriceFacts customer(String cart, CartAccess access, long version) {
                    assertThat(access.accessToken()).isEqualTo("secret"); calls.incrementAndGet();
                    throw BizException.of(ErrorCode.FORBIDDEN);
                }
            };
        }
    }
}
