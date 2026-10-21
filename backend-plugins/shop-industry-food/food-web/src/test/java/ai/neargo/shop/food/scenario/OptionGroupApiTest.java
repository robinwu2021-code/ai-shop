package ai.neargo.shop.food.scenario;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;

import ai.neargo.shop.auth.LoginUser;
import ai.neargo.shop.auth.TokenStore;
import ai.neargo.shop.auth.store.MemoryTokenStore;
import ai.neargo.shop.common.PageData;
import ai.neargo.shop.food.config.FoodWebAutoConfiguration;
import ai.neargo.shop.food.dto.CreateOptionGroupReq;
import ai.neargo.shop.food.dto.CreateOptionGroupResultVO;
import ai.neargo.shop.food.dto.ListOptionGroupsQuery;
import ai.neargo.shop.food.dto.OptionGroupConfigVO;
import ai.neargo.shop.food.dto.OptionGroupResultVO;
import ai.neargo.shop.food.service.OptionGroupService;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
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

/** Verifies the original auth, ownership and response envelope before reaching configuration service. */
class OptionGroupApiTest {
    private static final String ROOT = "/biz/x/food/stores/A/option-groups";
    private static final String BODY = "{\"name\":\"糖度\",\"minQty\":0,\"maxQty\":1}";
    private final WebApplicationContextRunner runner = new WebApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(FoodWebAutoConfiguration.class))
            .withUserConfiguration(FoodPolicyApiTest.PolicyHost.class, DishApiTest.Security.class, Host.class)
            .withPropertyValues("shop.food.enabled=true", "spring.profiles.active=api");

    @Test void createsAndQueriesUsingTrustedStoreEntityActorAndGoodsPermission() {
        runner.run(c -> {
            var mvc = mvc(c); var auth = token(c, "staff");
            mvc.perform(post(ROOT).header("Authorization", auth).header("Idempotency-Key", "original")
                    .contentType(MediaType.APPLICATION_JSON).content(BODY))
                    .andExpect(jsonPath("$.code").value(0)).andExpect(jsonPath("$.data.configNo").value("OG"));
            mvc.perform(get(ROOT).header("Authorization", auth))
                    .andExpect(jsonPath("$.data.total").value(1))
                    .andExpect(jsonPath("$.data.records[0].kind").value("OPTION_GROUP"));
            mvc.perform(get(ROOT + "/OG").header("Authorization", auth))
                    .andExpect(jsonPath("$.data.config.data.name").value("糖度"));
            assertThat(c.getBean(Calls.class).count).hasValue(3);
        });
    }

    @Test void rejectsForgedFieldsMissingKeyAndInvalidAmountsWithoutCallingService() {
        runner.run(c -> {
            var mvc = mvc(c); var auth = token(c, "staff");
            for (String body : List.of(BODY.replace("\"name\"", "\"entityNo\":\"OTHER\",\"name\""),
                    BODY.replace("\"name\"", "\"price\":1,\"name\""), BODY.replace(":0", ":-1"),
                    BODY.replace(":1", ":0.0000001"), BODY.replace("糖度", " "))) {
                mvc.perform(post(ROOT).header("Authorization", auth).header("Idempotency-Key", "original")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                        .andExpect(jsonPath("$.code").value(10400));
            }
            mvc.perform(post(ROOT).header("Authorization", auth).contentType(MediaType.APPLICATION_JSON).content(BODY))
                    .andExpect(jsonPath("$.code").value(10400));
            assertThat(c.getBean(Calls.class).count).hasValue(0);
        });
    }

    @Test void rejectsStoreMismatchMissingPermissionAndUnknownOrDuplicateQueryFields() {
        runner.run(c -> {
            var mvc = mvc(c); var auth = token(c, "staff");
            for (String path : List.of(ROOT, ROOT + "/OG")) {
                mvc.perform(get(path).header("Authorization", token(c, "cashier")))
                        .andExpect(jsonPath("$.code").value(70006));
                mvc.perform(get(path.replace("/A/", "/B/")).header("Authorization", auth))
                        .andExpect(jsonPath("$.code").value(10403));
                mvc.perform(get(path).header("Authorization", auth).param("entityNo", "OTHER"))
                        .andExpect(jsonPath("$.code").value(10400));
            }
            mvc.perform(get(ROOT).header("Authorization", auth).param("page", "1", "2"))
                    .andExpect(jsonPath("$.code").value(10400));
            mvc.perform(get(ROOT).header("Authorization", auth).param("size", "101"))
                    .andExpect(jsonPath("$.code").value(10400));
            mvc.perform(post(ROOT).header("Authorization", token(c, "cashier"))
                    .header("Idempotency-Key", "original").contentType(MediaType.APPLICATION_JSON).content(BODY))
                    .andExpect(jsonPath("$.code").value(70006));
            assertThat(c.getBean(Calls.class).count).hasValue(0);
        });
    }

    private String token(WebApplicationContext c, String actor) {
        return "Bearer " + c.getBean(MemoryTokenStore.class)
                .issue(TokenStore.SessionData.of(LoginUser.merchant(actor, actor)));
    }
    private MockMvc mvc(WebApplicationContext c) {
        return MockMvcBuilders.webAppContextSetup(c).addFilters(c.getBean(FilterChainProxy.class)).build();
    }
    static class Calls { final AtomicInteger count = new AtomicInteger(); }
    @Configuration(proxyBeanMethods = false)
    static class Host {
        @Bean Calls optionGroupCalls() { return new Calls(); }
        @Bean OptionGroupService optionGroupService(Calls calls) {
            return new OptionGroupService() {
                private void scope(String entity, String store) {
                    assertThat(entity).isEqualTo("E"); assertThat(store).isEqualTo("A");
                    calls.count.incrementAndGet();
                }
                private OptionGroupConfigVO group() {
                    return new OptionGroupConfigVO("OG", "OPTION_GROUP", 0, "DRAFT",
                            new OptionGroupConfigVO.Data("糖度", BigDecimal.ZERO, BigDecimal.ONE, null,
                                    Map.of(), List.of()));
                }
                @Override public CreateOptionGroupResultVO createOptionGroup(String entity, String store,
                        String actor, CreateOptionGroupReq req, String key) {
                    scope(entity, store);
                    assertThat(actor).isEqualTo("staff"); assertThat(key).isEqualTo("original");
                    return new CreateOptionGroupResultVO("OG", 0, "DRAFT");
                }
                @Override public OptionGroupResultVO getOptionGroup(String entity, String store, String config) {
                    scope(entity, store); assertThat(config).isEqualTo("OG"); return new OptionGroupResultVO(group());
                }
                @Override public PageData<OptionGroupConfigVO> listOptionGroups(String entity, String store,
                        ListOptionGroupsQuery query) {
                    scope(entity, store); return PageData.of(List.of(group()), 1, 1, 20);
                }
            };
        }
    }
}
