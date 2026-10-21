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
import ai.neargo.shop.food.service.ConfigRevisionService;
import ai.neargo.shop.food.dto.ConfigRevisionVO;
import ai.neargo.shop.food.dto.PublishConfigResultVO;
import java.time.OffsetDateTime;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
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
class ConfigRevisionApiTest {
    @Test void bundlePublicationUsesOriginalScopeAndRejectsForeignStoresAndFrozenInput() {
        runner.run(c -> {
            var mvc = mvc(c); var auth = token(c, "staff");
            String path = "/biz/x/food/stores/A/bundles/MEAL:publish";
            mvc.perform(post(path).header("Authorization", auth).header("Idempotency-Key", "bundle")
                    .contentType(MediaType.APPLICATION_JSON).content(BODY))
                    .andExpect(jsonPath("$.data.publication.configKind").value("BUNDLE"));
            mvc.perform(get("/biz/x/food/config-revisions").header("Authorization", auth)
                    .param("configKind", "BUNDLE").param("configNo", "MEAL"))
                    .andExpect(jsonPath("$.data.total").value(0));
            mvc.perform(post(path.replace("/A/", "/B/")).header("Authorization", auth)
                    .header("Idempotency-Key", "foreign").contentType(MediaType.APPLICATION_JSON).content(BODY))
                    .andExpect(jsonPath("$.code").value(10403));
            mvc.perform(post(path).header("Authorization", token(c, "cashier"))
                    .header("Idempotency-Key", "readonly").contentType(MediaType.APPLICATION_JSON).content(BODY))
                    .andExpect(jsonPath("$.code").value(70006));
            for (String field : List.of("definitionSnapshot", "price", "entityNo", "scopeStoreNos")) {
                mvc.perform(post(path).header("Authorization", auth).header("Idempotency-Key", "bad")
                        .contentType(MediaType.APPLICATION_JSON).content(BODY.replace("{", "{\"" + field + "\":{},")))
                        .andExpect(jsonPath("$.code").value(10400));
            }
            assertThat(c.getBean(Calls.class).count).hasValue(2);
        });
    }
    private static final String ROOT = "/biz/x/food/stores/A/modifiers";
    private static final String BODY = "{\"version\":0,\"effectiveAt\":\"2026-10-21T08:00:00Z\"}";
    private final WebApplicationContextRunner runner = new WebApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(FoodWebAutoConfiguration.class))
            .withUserConfiguration(FoodPolicyApiTest.PolicyHost.class, DishApiTest.Security.class, Host.class)
            .withPropertyValues("shop.food.enabled=true", "spring.profiles.active=api");

    @Test void publicPublishAndHistoryUseTrustedScopeAndOriginalEnvelope() {
        runner.run(c -> {
            var mvc = mvc(c); var auth = token(c, "staff");
            for (String path : List.of("/biz/x/food/modifiers/MOD:publish",
                    "/biz/x/food/stores/A/option-groups/GROUP:publish")) {
                mvc.perform(post(path).header("Authorization", auth).header("Idempotency-Key", "key")
                        .contentType(MediaType.APPLICATION_JSON).content(BODY))
                        .andExpect(jsonPath("$.data.publication.revisionNo").value("REV"));
            }
            mvc.perform(get("/biz/x/food/config-revisions").header("Authorization", auth)
                    .param("configKind", "MODIFIER").param("configNo", "MOD"))
                    .andExpect(jsonPath("$.data.total").value(0));
            assertThat(c.getBean(Calls.class).count).hasValue(3);
        });
    }
    @Test void forbiddenStoreUnknownFieldsAndMissingKeyDoNotReachPublication() {
        runner.run(c -> {
            var mvc = mvc(c); var auth = token(c, "staff");
            String path = "/biz/x/food/stores/A/option-groups/GROUP:publish";
            mvc.perform(post(path).header("Authorization", token(c, "cashier"))
                    .header("Idempotency-Key", "key").contentType(MediaType.APPLICATION_JSON).content(BODY))
                    .andExpect(jsonPath("$.code").value(70006));
            mvc.perform(post(path.replace("/A/", "/B/")).header("Authorization", auth)
                    .header("Idempotency-Key", "key").contentType(MediaType.APPLICATION_JSON).content(BODY))
                    .andExpect(jsonPath("$.code").value(10403));
            mvc.perform(post(path).header("Authorization", auth).header("Idempotency-Key", "key")
                    .contentType(MediaType.APPLICATION_JSON).content(BODY.replace("{", "{\"definitionSnapshot\":{},")))
                    .andExpect(jsonPath("$.code").value(10400));
            mvc.perform(post(path).header("Authorization", auth).contentType(MediaType.APPLICATION_JSON).content(BODY))
                    .andExpect(jsonPath("$.code").value(10400));
            mvc.perform(post(path).header("Authorization", auth).header("Idempotency-Key", "key")
                    .contentType(MediaType.APPLICATION_JSON).content(BODY.replace("\"version\":0", "\"version\":0.5")))
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
    @Configuration(proxyBeanMethods=false)
    static class Host {
        @Bean Calls publicationCalls() { return new Calls(); }
        @Bean ConfigRevisionService configRevisions(Calls calls) {
            var service = mock(ConfigRevisionService.class);
            when(service.publishBundle(anyString(), anyString(), anyString(), anyString(), any(), anyString()))
                    .thenAnswer(i -> {
                        assertThat((String)i.getArgument(0)).isEqualTo("E");
                        assertThat((String)i.getArgument(1)).isEqualTo("A");
                        assertThat((String)i.getArgument(2)).isEqualTo("MEAL");
                        assertThat((String)i.getArgument(3)).isEqualTo("staff");
                        calls.count.incrementAndGet();
                        return new PublishConfigResultVO(new ConfigRevisionVO("BUNDLE_REV", "BUNDLE", "MEAL", 1,
                                OffsetDateTime.parse("2026-10-21T08:00:00Z"), "a".repeat(64), "EFFECTIVE"));
                    });
            var receipt = new PublishConfigResultVO(new ConfigRevisionVO("REV", "MODIFIER", "MOD", 1,
                    OffsetDateTime.parse("2026-10-21T08:00:00Z"), "a".repeat(64), "EFFECTIVE"));
            when(service.publishModifier(anyString(), anyString(), anyString(), anyString(), any(), anyString()))
                    .thenAnswer(i -> {
                        assertThat((String)i.getArgument(0)).isEqualTo("E");
                        assertThat((String)i.getArgument(1)).isEqualTo("A");
                        assertThat((String)i.getArgument(3)).isEqualTo("staff");
                        calls.count.incrementAndGet(); return receipt;
                    });
            when(service.publishOptionGroup(anyString(), anyString(), anyString(), anyString(), any(), anyString()))
                    .thenAnswer(i -> { calls.count.incrementAndGet(); return receipt; });
            when(service.listRevisions(anyString(), anyString(), any())).thenAnswer(i -> {
                assertThat((String)i.getArgument(0)).isEqualTo("E");
                assertThat((String)i.getArgument(1)).isEqualTo("A");
                calls.count.incrementAndGet(); return PageData.of(List.of(), 0, 1, 20);
            });
            return service;
        }
    }
}
