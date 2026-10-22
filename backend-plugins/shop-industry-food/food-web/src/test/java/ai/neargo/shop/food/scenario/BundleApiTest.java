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
import ai.neargo.shop.food.dto.CreateBundleReq;
import ai.neargo.shop.food.dto.GetBundleVO;
import ai.neargo.shop.food.dto.ReplaceBundleDefinitionReq;
import ai.neargo.shop.food.dto.BundleMutationVO;
import ai.neargo.shop.food.dto.CreateBundleResultVO;
import ai.neargo.shop.food.dto.ListBundlesQuery;
import ai.neargo.shop.food.dto.BundleConfigVO;
import ai.neargo.shop.food.service.BundleService;
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
class BundleApiTest {
    private static final String ROOT = "/biz/x/food/stores/A/bundles";
    private static final String BODY = "{\"skuNo\":\"MEAL\"}";
    private final WebApplicationContextRunner runner = new WebApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(FoodWebAutoConfiguration.class))
            .withUserConfiguration(FoodPolicyApiTest.PolicyHost.class, DishApiTest.Security.class, Host.class)
            .withPropertyValues("shop.food.enabled=true", "spring.profiles.active=api");

    @Test void createUpdateAndListResolveTrustedScopeAndOriginalEnvelope() {
        runner.run(c -> {
            var mvc = mvc(c); var auth = token(c, "staff");
            mvc.perform(post(ROOT).header("Authorization", auth).header("Idempotency-Key", "original")
                    .contentType(MediaType.APPLICATION_JSON).content(BODY))
                    .andExpect(jsonPath("$.data.configNo").value("MEAL"));
            mvc.perform(get(ROOT).header("Authorization", auth))
                    .andExpect(jsonPath("$.data.total").value(1));
            mvc.perform(put("/biz/x/food/bundles/MEAL/definition").header("Authorization", auth)
                    .header("Idempotency-Key", "original").contentType(MediaType.APPLICATION_JSON)
                    .content("{\"version\":0,\"groups\":[],\"choices\":[]}"))
                    .andExpect(jsonPath("$.data.version").value(1));
            mvc.perform(get(ROOT + "/MEAL").header("Authorization", auth))
                    .andExpect(jsonPath("$.data.config.configNo").value("MEAL"));
            mvc.perform(put("/biz/x/food/bundles/MEAL/definition").header("Authorization", auth)
                    .header("Idempotency-Key", "fractional").contentType(MediaType.APPLICATION_JSON)
                    .content("{\"version\":0.5,\"groups\":[],\"choices\":[]}"))
                    .andExpect(jsonPath("$.code").value(10400));
            assertThat(c.getBean(Calls.class).count).hasValue(4);
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
                    BODY.replace("{", "{\"entityNo\":\"OTHER\","))) {
                mvc.perform(post(ROOT).header("Authorization", auth).header("Idempotency-Key", "original")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                        .andExpect(jsonPath("$.code").value(10400));
            }
            mvc.perform(put("/biz/x/food/bundles/MEAL/definition").header("Authorization", auth)
                    .header("Idempotency-Key", "nested-field").contentType(MediaType.APPLICATION_JSON)
                    .content("{\"version\":0,\"groups\":[{\"groupNo\":\"G\",\"name\":\"x\","
                            + "\"minQty\":0,\"maxQty\":1,\"sortNo\":0,\"price\":2}],\"choices\":[]}"))
                    .andExpect(jsonPath("$.code").value(10400));
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
        @Bean Calls bundleCalls() { return new Calls(); }
        @Bean BundleService bundleService(Calls calls) {
            return new BundleService() {
                private void scope(String entity, String store) {
                    assertThat(entity).isEqualTo("E"); assertThat(store).isEqualTo("A");
                    calls.count.incrementAndGet();
                }
                @Override public CreateBundleResultVO createBundle(String entity, String store, String actor,
                        CreateBundleReq req, String key) {
                    scope(entity, store); assertThat(actor).isEqualTo("staff");
                    return new CreateBundleResultVO("MEAL", 0, "DRAFT");
                }
                @Override public BundleMutationVO replaceDefinition(String entity, String store, String number,
                        String actor, ReplaceBundleDefinitionReq req, String key) {
                    scope(entity, store); assertThat(actor).isEqualTo("staff"); assertThat(number).isEqualTo("MEAL");
                    return new BundleMutationVO(number, 1);
                }
                @Override public GetBundleVO getBundle(String entity, String store, String number) {
                    scope(entity, store); assertThat(number).isEqualTo("MEAL");
                    return new GetBundleVO(new BundleConfigVO("MEAL", 0, "DRAFT",
                            new BundleConfigVO.Data(0, null, List.of(), List.of())));
                }
                @Override public PageData<BundleConfigVO> listBundles(String entity, String store,
                        ListBundlesQuery query) {
                    scope(entity, store);
                    return PageData.of(List.of(new BundleConfigVO("MEAL", 0, "DRAFT",
                            new BundleConfigVO.Data(0, null, List.of(), List.of()))), 1, 1, 20);
                }
            };
        }
    }
}
