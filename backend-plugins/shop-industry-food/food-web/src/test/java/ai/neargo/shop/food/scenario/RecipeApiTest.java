package ai.neargo.shop.food.scenario;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;

import ai.neargo.shop.auth.LoginUser;
import ai.neargo.shop.auth.TokenStore;
import ai.neargo.shop.auth.store.MemoryTokenStore;
import ai.neargo.shop.common.PageData;
import ai.neargo.shop.food.config.FoodWebAutoConfiguration;
import ai.neargo.shop.food.dto.CreateRecipeReq;
import ai.neargo.shop.food.dto.ListRecipesQuery;
import ai.neargo.shop.food.dto.RecipeResultVO;
import ai.neargo.shop.food.dto.RecipeVO;
import ai.neargo.shop.food.dto.ReplaceRecipeDefinitionReq;
import ai.neargo.shop.food.service.RecipeService;
import java.math.BigDecimal;
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
class RecipeApiTest {
    private static final String ROOT = "/biz/x/food/stores/A/recipes";
    private static final String BODY = "{\"skuNo\":\"TEA\",\"recipeCode\":\"DEFAULT\","
            + "\"yieldQty\":1,\"yieldUnit\":\"EA\"}";
    private final WebApplicationContextRunner runner = new WebApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(FoodWebAutoConfiguration.class))
            .withUserConfiguration(FoodPolicyApiTest.PolicyHost.class, DishApiTest.Security.class, Host.class)
            .withPropertyValues("shop.food.enabled=true", "spring.profiles.active=api");

    @Test void createEditDetailAndFilteredListUseTrustedStoreAndOriginalResponseEnvelope() {
        runner.run(c -> {
            var mvc = mvc(c); var auth = token(c, "staff");
            mvc.perform(post(ROOT).header("Authorization", auth).header("Idempotency-Key", "create")
                    .contentType(MediaType.APPLICATION_JSON).content(BODY))
                    .andExpect(jsonPath("$.data.recipe.recipeNo").value("R1"));
            mvc.perform(get(ROOT).header("Authorization", auth).param("skuNo", "TEA").param("status", "DRAFT"))
                    .andExpect(jsonPath("$.data.total").value(1));
            mvc.perform(get("/biz/x/food/recipes/R1").header("Authorization", auth))
                    .andExpect(jsonPath("$.data.recipe.storeNo").value("A"));
            mvc.perform(put("/biz/x/food/recipes/R1/definition").header("Authorization", auth)
                    .header("Idempotency-Key", "edit").contentType(MediaType.APPLICATION_JSON)
                    .content("{\"version\":0,\"yieldQty\":1,\"yieldUnit\":\"EA\",\"lines\":[]}"))
                    .andExpect(jsonPath("$.data.recipe.version").value(1));
            assertThat(c.getBean(Calls.class).count).hasValue(4);
        });
    }

    @Test void blocksForeignStorePermissionOwnershipFieldsUnknownAdjustmentAndNumericText() {
        runner.run(c -> {
            var mvc = mvc(c); var auth = token(c, "staff");
            mvc.perform(get(ROOT).header("Authorization", token(c, "cashier")))
                    .andExpect(jsonPath("$.code").value(70006));
            mvc.perform(get(ROOT.replace("/A/", "/B/")).header("Authorization", auth))
                    .andExpect(jsonPath("$.code").value(10403));
            mvc.perform(get(ROOT).header("Authorization", auth).param("entityNo", "OTHER"))
                    .andExpect(jsonPath("$.code").value(10400));
            for (String bad : List.of(BODY.replace("{", "{\"entityNo\":\"OTHER\","),
                    BODY.replace("\"TEA\"", "123"), BODY.replace("{", "{\"adjustments\":[],"))) {
                mvc.perform(post(ROOT).header("Authorization", auth).header("Idempotency-Key", "bad")
                        .contentType(MediaType.APPLICATION_JSON).content(bad))
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
        @Bean Calls recipeCalls() { return new Calls(); }
        @Bean RecipeService recipeService(Calls calls) {
            return new RecipeService() {
                private RecipeResultVO result(String entity, String store, long version) {
                    assertThat(entity).isEqualTo("E"); assertThat(store).isEqualTo("A");
                    calls.count.incrementAndGet();
                    return new RecipeResultVO(new RecipeVO("R1", store, "TEA", "DEFAULT", BigDecimal.ONE,
                            "EA", "DRAFT", version, version, List.of()));
                }
                @Override public RecipeResultVO createRecipe(String entity, String store, String actor,
                        CreateRecipeReq req, String key) {
                    assertThat(actor).isEqualTo("staff"); return result(entity, store, 0);
                }
                @Override public RecipeResultVO replaceDefinition(String entity, String store, String id,
                        String actor, ReplaceRecipeDefinitionReq req, String key) {
                    assertThat(id).isEqualTo("R1"); return result(entity, store, 1);
                }
                @Override public RecipeResultVO getRecipe(String entity, String store, String id) {
                    assertThat(id).isEqualTo("R1"); return result(entity, store, 0);
                }
                @Override public PageData<RecipeVO> listRecipes(String entity, String store, ListRecipesQuery q) {
                    assertThat(q.skuNo()).isEqualTo("TEA"); assertThat(q.status()).isEqualTo("DRAFT");
                    return PageData.of(List.of(result(entity, store, 0).recipe()), 1, 1, 20);
                }
            };
        }
    }
}
