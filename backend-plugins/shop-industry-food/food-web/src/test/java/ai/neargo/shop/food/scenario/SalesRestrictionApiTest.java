package ai.neargo.shop.food.scenario;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;

import ai.neargo.shop.auth.LoginUser;
import ai.neargo.shop.auth.TokenStore;
import ai.neargo.shop.auth.store.MemoryTokenStore;
import ai.neargo.shop.common.PageData;
import ai.neargo.shop.food.config.FoodWebAutoConfiguration;
import ai.neargo.shop.food.dto.SalesRestrictionReq;
import ai.neargo.shop.food.dto.SalesRestrictionQuery;
import ai.neargo.shop.food.dto.SalesRestrictionVO;
import ai.neargo.shop.food.dto.SalesRestrictionEvaluationVO;
import ai.neargo.shop.food.service.SalesRestrictionService;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.MediaType;
import org.springframework.security.web.FilterChainProxy;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

class SalesRestrictionApiTest {
    private static final String ROOT = "/biz/x/food/stores/A/sales-restrictions";
    private static final String BODY = "{\"skuNo\":\"S\",\"mode\":\"SOLD_OUT\",\"reason\":\"x\"}";
    private final WebApplicationContextRunner runner = new WebApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(FoodWebAutoConfiguration.class))
            .withUserConfiguration(FoodPolicyApiTest.PolicyHost.class, DishApiTest.Security.class, Host.class)
            .withPropertyValues("shop.food.enabled=true", "spring.profiles.active=api");
    @Test void authorizedMutationUsesTrustedScopeAndExistingGoodsPermission() {
        runner.run(c -> {
            var mvc = mvc(c); var auth = token(c, "staff");
            mvc.perform(post(ROOT).header("Authorization", auth).header("Idempotency-Key", "key")
                    .contentType(MediaType.APPLICATION_JSON).content(BODY))
                    .andExpect(jsonPath("$.data.restrictionNo").value("R"));
            mvc.perform(get(ROOT).header("Authorization", auth))
                    .andExpect(jsonPath("$.data.total").value(0));
            assertThat(c.getBean(AtomicInteger.class)).hasValue(2);
        });
    }
    @Test void rejectsForeignStoreIdentityUnknownQueryAndCoercedQuotaBeforeService() {
        runner.run(c -> {
            var mvc = mvc(c); var auth = token(c, "staff");
            mvc.perform(get(ROOT).header("Authorization", token(c, "cashier")))
                    .andExpect(jsonPath("$.code").value(70006));
            mvc.perform(get(ROOT.replace("/A/", "/B/")).header("Authorization", auth))
                    .andExpect(jsonPath("$.code").value(10403));
            mvc.perform(get(ROOT).header("Authorization", auth).param("entityNo", "OTHER"))
                    .andExpect(jsonPath("$.code").value(10400));
            mvc.perform(get(ROOT).header("Authorization", auth).param("status", "ACTIVE", "CLEARED"))
                    .andExpect(jsonPath("$.code").value(10400));
            for (String bad : List.of(BODY.replace("{", "{\"entityNo\":\"OTHER\","),
                    BODY.replace("\"S\"", "12"), BODY.replace("SOLD_OUT", "QUOTA").replace("{", "{\"quota\":\"2\","))) {
                mvc.perform(post(ROOT).header("Authorization", auth).header("Idempotency-Key", "bad")
                        .contentType(MediaType.APPLICATION_JSON).content(bad))
                        .andExpect(jsonPath("$.code").value(10400));
            }
            assertThat(c.getBean(AtomicInteger.class)).hasValue(0);
        });
    }
    @Test void rejectsUnsupportedSalesScopesBeforeCallingService() {
        runner.run(c -> {
            var mvc=mvc(c); var auth=token(c,"staff");
            for (String extra:List.of("\"orderSource\":\"WEB\"", "\"fulfillment\":\"PICK_UP\"",
                    "\"orderSource\":\"CHANNEL\",\"fulfillment\":\"DINE_IN\"")) {
                mvc.perform(post(ROOT).header("Authorization",auth).header("Idempotency-Key","bad-scope")
                        .contentType(MediaType.APPLICATION_JSON).content(BODY.replace("{","{"+extra+",")))
                        .andExpect(jsonPath("$.code").value(10400));
            }
            assertThat(c.getBean(AtomicInteger.class)).hasValue(0);
        });
    }
    private String token(WebApplicationContext c, String actor) {
        return "Bearer " + c.getBean(MemoryTokenStore.class)
                .issue(TokenStore.SessionData.of(LoginUser.merchant(actor, actor)));
    }
    private MockMvc mvc(WebApplicationContext c) {
        return MockMvcBuilders.webAppContextSetup(c).addFilters(c.getBean(FilterChainProxy.class)).build();
    }
    @Configuration(proxyBeanMethods=false)
    static class Host {
        @Bean AtomicInteger restrictionCalls() { return new AtomicInteger(); }
        @Bean SalesRestrictionService restrictions(AtomicInteger calls) {
            return new SalesRestrictionService() {
                @Override public SalesRestrictionVO manage(String entity, String store, String actor,
                        SalesRestrictionReq req, String key) {
                    assertThat(entity).isEqualTo("E"); assertThat(store).isEqualTo("A");
                    assertThat(actor).isEqualTo("staff"); calls.incrementAndGet();
                    return new SalesRestrictionVO("R", store, "S", "SOLD_OUT", null, null, BigDecimal.ZERO,
                            BigDecimal.ZERO, Instant.parse("2026-10-21T12:00:00Z"), null,
                            "ACTIVE", "x", null, null, 0L);
                }
                @Override public PageData<SalesRestrictionVO> list(String entity, String store,
                        SalesRestrictionQuery q) {
                    assertThat(entity).isEqualTo("E"); assertThat(store).isEqualTo("A"); calls.incrementAndGet();
                    return PageData.empty(1, 20);
                }
                @Override public SalesRestrictionEvaluationVO evaluate(String e, String s, String sku, String
                        o, String f) {
                    throw new UnsupportedOperationException();
                }
            };
        }
    }
}
