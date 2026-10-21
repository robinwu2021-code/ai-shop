package ai.neargo.shop.food.scenario;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;

import ai.neargo.shop.auth.LoginUser;
import ai.neargo.shop.auth.TokenStore;
import ai.neargo.shop.auth.store.MemoryTokenStore;
import ai.neargo.shop.common.PageData;
import ai.neargo.shop.food.config.FoodWebAutoConfiguration;
import ai.neargo.shop.food.dto.CreateModifierReq;
import ai.neargo.shop.food.dto.UpdateModifierReq;
import ai.neargo.shop.food.dto.ModifierMutationVO;
import ai.neargo.shop.food.dto.CreateModifierResultVO;
import ai.neargo.shop.food.dto.ListModifiersQuery;
import ai.neargo.shop.food.dto.ModifierConfigVO;
import ai.neargo.shop.food.service.ModifierService;
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

/** Verifies the original auth, ownership and response envelope before reaching configuration service. */
class ModifierApiTest {
    private static final String ROOT = "/biz/x/food/stores/A/modifiers";
    private static final String BODY = "{\"name\":\"珍珠\",\"kind\":\"ADDON\",\"components\":[]}";
    private final WebApplicationContextRunner runner = new WebApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(FoodWebAutoConfiguration.class))
            .withUserConfiguration(FoodPolicyApiTest.PolicyHost.class, DishApiTest.Security.class, Host.class)
            .withPropertyValues("shop.food.enabled=true", "spring.profiles.active=api");

    @Test void createUpdateAndListResolveTrustedScopeAndOriginalEnvelope() {
        runner.run(c -> {
            var mvc = mvc(c); var auth = token(c, "staff");
            mvc.perform(post(ROOT).header("Authorization", auth).header("Idempotency-Key", "original")
                    .contentType(MediaType.APPLICATION_JSON).content(BODY))
                    .andExpect(jsonPath("$.data.configNo").value("MOD"));
            mvc.perform(get(ROOT).header("Authorization", auth))
                    .andExpect(jsonPath("$.data.total").value(1));
            mvc.perform(put("/biz/x/food/modifiers/MOD").header("Authorization", auth)
                    .header("Idempotency-Key", "original").contentType(MediaType.APPLICATION_JSON)
                    .content(BODY.replace("{", "{\"version\":0,")))
                    .andExpect(jsonPath("$.data.version").value(1));
            assertThat(c.getBean(Calls.class).count).hasValue(3);
        });
    }

    @Test void rejectsScopePermissionUnknownFieldsMissingKeyAndMalformedMembers() {
        runner.run(c -> {
            var mvc = mvc(c); var auth = token(c, "staff");
            mvc.perform(get(ROOT).header("Authorization", token(c, "cashier")))
                    .andExpect(jsonPath("$.code").value(70006));
            mvc.perform(get(ROOT.replace("/A/", "/B/")).header("Authorization", auth))
                    .andExpect(jsonPath("$.code").value(10403));
            mvc.perform(get(ROOT).header("Authorization", auth).param("entityNo", "OTHER"))
                    .andExpect(jsonPath("$.code").value(10400));
            for (String body : List.of(BODY.replace("{", "{\"price\":1,"),
                    BODY.replace("[]", "[{\"memberNo\":\"A\",\"quantity\":0}]"),
                    BODY.replace("[]", "[{\"memberNo\":\"A\",\"quantity\":1,\"stock\":2}]"))) {
                mvc.perform(post(ROOT).header("Authorization", auth).header("Idempotency-Key", "original")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                        .andExpect(jsonPath("$.code").value(10400));
            }
            mvc.perform(post(ROOT).header("Authorization", auth).contentType(MediaType.APPLICATION_JSON).content(BODY))
                    .andExpect(jsonPath("$.code").value(10400));
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
        @Bean Calls modifierCalls() { return new Calls(); }
        @Bean ModifierService modifierService(Calls calls) {
            return new ModifierService() {
                private void scope(String entity, String store) {
                    assertThat(entity).isEqualTo("E"); assertThat(store).isEqualTo("A");
                    calls.count.incrementAndGet();
                }
                @Override public CreateModifierResultVO createModifier(String entity, String store, String actor,
                        CreateModifierReq req, String key) {
                    scope(entity, store); assertThat(actor).isEqualTo("staff");
                    return new CreateModifierResultVO("MOD", 0, "DRAFT");
                }
                @Override public ModifierMutationVO updateModifier(String entity, String store, String number,
                        String actor, UpdateModifierReq req, String key) {
                    scope(entity, store); assertThat(actor).isEqualTo("staff"); assertThat(number).isEqualTo("MOD");
                    return new ModifierMutationVO(number, 1);
                }
                @Override public PageData<ModifierConfigVO> listModifiers(String entity, String store,
                        ListModifiersQuery query) {
                    scope(entity, store);
                    return PageData.of(List.of(new ModifierConfigVO("MOD", "ADDON", 0, "DRAFT",
                            new ModifierConfigVO.Data("珍珠", null, null, List.of()))), 1, 1, 20);
                }
            };
        }
    }
}
