package ai.neargo.shop.food.scenario;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import ai.neargo.shop.auth.BizContextFilter;
import ai.neargo.shop.auth.LivePermResolver;
import ai.neargo.shop.auth.LoginUser;
import ai.neargo.shop.auth.PermChecker;
import ai.neargo.shop.auth.TokenStore;
import ai.neargo.shop.auth.store.MemoryTokenStore;
import ai.neargo.shop.common.ApiResponseWrapper;
import ai.neargo.shop.common.GlobalExceptionHandler;
import ai.neargo.shop.food.config.FoodWebAutoConfiguration;
import ai.neargo.shop.food.dto.CreatePolicyReq;
import ai.neargo.shop.food.dto.GetPolicyQuery;
import ai.neargo.shop.food.dto.OperationVO;
import ai.neargo.shop.food.dto.StoreOperationPolicyResultVO;
import ai.neargo.shop.food.service.StoreOperationService;
import ai.neargo.shop.spi.user.MerchantQueryPort;
import ai.neargo.shop.spi.user.StoreDirectoryPort;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.MediaType;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.web.FilterChainProxy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;

class FoodPolicyApiTest {
    private final WebApplicationContextRunner runner = new WebApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(FoodWebAutoConfiguration.class))
            .withUserConfiguration(PolicySecurity.class, PolicyHost.class)
            .withPropertyValues("shop.food.enabled=true", "spring.profiles.active=api");
    @Test void readsSixFieldPolicyThroughActualBEndpointAndSharedEnvelope() {
        runner.run(context -> {
            assertThat(context).hasNotFailed();
            mvc(context).perform(get("/biz/x/food/stores/A/policy").header("Authorization", token(context,"staff"))
                            .param("at","2026-10-20T20:00:00+08:00"))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.code").value(0))
                    .andExpect(jsonPath("$.data.policyNo").value("P1"))
                    .andExpect(jsonPath("$.data.capabilities[0]").value("TABLE_CONFIG"))
                    .andExpect(jsonPath("$.data.id").doesNotExist())
                    .andExpect(jsonPath("$.data.settings").doesNotExist());
            assertThat(context.getBean(Calls.class).at.get()).isEqualTo(Instant.parse("2026-10-20T12:00:00Z"));
        });
    }
    @Test void invalidStoreHeaderStillRejectsAfterSharedDefaultFallback() {
        runner.run(context -> {
            mvc(context).perform(get("/biz/x/food/stores/A/policy").header("Authorization",token(context,"staff"))
                    .header(BizContextFilter.STORE_HEADER,"OTHER"))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.code").value(10403));
            assertThat(context.getBean(Calls.class).count).hasValue(0);
        });
    }
    @Test void rejectsPathStoreDifferentFromCurrentStore() {
        runner.run(context -> {
            mvc(context).perform(get("/biz/x/food/stores/B/policy").header("Authorization",token(context,"staff")))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.code").value(10403));
            assertThat(context.getBean(Calls.class).count).hasValue(0);
        });
    }
    @Test void deniesStorePermissionBeforeQuery() {
        runner.run(context -> {
            mvc(context).perform(get("/biz/x/food/stores/A/policy").header("Authorization",token(context,"cashier")))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.code").value(70006));
            assertThat(context.getBean(Calls.class).count).hasValue(0);
        });
    }
    @Test void invalidDatetimeIsBadRequest() {
        runner.run(context -> mvc(context).perform(get("/biz/x/food/stores/A/policy")
                .header("Authorization",token(context,"staff")).param("at","not-an-instant"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.code").value(10400)));
    }
    @Test void callerCannotReplaceEntityWithQueryParameters() {
        runner.run(context -> mvc(context).perform(get("/biz/x/food/stores/A/policy")
                        .header("Authorization",token(context,"staff")).param("entityNo","FOREIGN"))
                .andExpect(status().isOk()));
    }
    @Test void rejectsDirectoryOwnerMismatchBeforePolicyQuery() {
        runner.run(context -> {
            context.getBean(Calls.class).owner.set("FOREIGN");
            mvc(context).perform(get("/biz/x/food/stores/A/policy").header("Authorization",token(context,"staff")))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.code").value(10403));
            assertThat(context.getBean(Calls.class).count).hasValue(0);
        });
    }
    @Test void missingDirectoryCardNeverQueriesPolicy() {
        runner.run(context -> {
            context.getBean(Calls.class).owner.set(null);
            mvc(context).perform(get("/biz/x/food/stores/A/policy").header("Authorization",token(context,"staff")))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.code").value(10404));
            assertThat(context.getBean(Calls.class).count).hasValue(0);
        });
    }
    private static final String PUBLICATION = "{\"timezone\":\"Asia/Shanghai\",\"cutoverMinute\":240,"
            + "\"effectiveFrom\":\"2026-10-22T12:00:00Z\",\"payPolicy\":\"POSTPAY\",\"capabilities\":[],\"version\":7}";
    @Test void publishesWithTrustedActorAndOriginalKeyThroughSharedEnvelope() {
        runner.run(context -> mvc(context).perform(post("/biz/x/food/stores/A/policies")
                        .header("Authorization", token(context,"staff")).header("Idempotency-Key","original")
                        .contentType(MediaType.APPLICATION_JSON).content(PUBLICATION))
                .andExpect(status().isOk()).andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.status").value("SUCCEEDED"))
                .andExpect(jsonPath("$.data.version").value(8)));
    }
    @Test void rejectsPublicationWithoutKeyOrPermissionOrWithInvalidStoreHeader() {
        runner.run(context -> {
            mvc(context).perform(post("/biz/x/food/stores/A/policies").header("Authorization",token(context,"staff"))
                            .contentType(MediaType.APPLICATION_JSON).content(PUBLICATION))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.code").value(10400));
            mvc(context).perform(post("/biz/x/food/stores/A/policies").header("Authorization",token(context,"cashier"))
                            .header("Idempotency-Key","original").contentType(MediaType.APPLICATION_JSON)
                            .content(PUBLICATION))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.code").value(70006));
            mvc(context).perform(post("/biz/x/food/stores/A/policies").header("Authorization",token(context,"staff"))
                            .header("Idempotency-Key","original").header(BizContextFilter.STORE_HEADER,"OTHER")
                            .contentType(MediaType.APPLICATION_JSON).content(PUBLICATION))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.code").value(10403));
            assertThat(context.getBean(Calls.class).count).hasValue(0);
        });
    }
    @Test void rejectsUnknownIdentityFieldsInsteadOfAcceptingCallerActor() {
        runner.run(context -> {
            mvc(context).perform(post("/biz/x/food/stores/A/policies").header("Authorization",token(context,"staff"))
                            .header("Idempotency-Key","original").contentType(MediaType.APPLICATION_JSON)
                            .content(PUBLICATION.replace("{\"timezone\"", "{\"actorNo\":\"forged\",\"timezone\"")))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.code").value(10400));
            assertThat(context.getBean(Calls.class).count).hasValue(0);
        });
    }
    private String token(WebApplicationContext context, String actor) {
        return "Bearer " + context.getBean(MemoryTokenStore.class)
                .issue(TokenStore.SessionData.of(LoginUser.merchant(actor,actor)));
    }
    private MockMvc mvc(WebApplicationContext context) {
        return MockMvcBuilders.webAppContextSetup(context).addFilters(context.getBean(FilterChainProxy.class)).build();
    }
    static class Calls {
        final AtomicInteger count = new AtomicInteger();
        final AtomicReference<Instant> at = new AtomicReference<>();
        final AtomicReference<String> owner = new AtomicReference<>("E");
    }
    @Configuration(proxyBeanMethods = false)
    @EnableWebMvc
    @EnableWebSecurity
    @EnableMethodSecurity
    static class PolicySecurity {
        @Bean MemoryTokenStore tokens() { return new FoodStoreScopeTest.Host().tokens(); }
        @Bean("perm") PermChecker perm(ObjectProvider<LivePermResolver> resolver) {
            return new FoodStoreScopeTest.Host().perm(resolver);
        }
        @Bean SecurityFilterChain chain(HttpSecurity http, MemoryTokenStore tokens) throws Exception {
            return new FoodStoreScopeTest.Host().chain(http, tokens);
        }
        @Bean GlobalExceptionHandler errors() { return new GlobalExceptionHandler(); }
    }
    @Configuration(proxyBeanMethods = false)
    static class PolicyHost {
        @Bean Calls calls() { return new Calls(); }
        @Bean ApiResponseWrapper responseWrapper() { return new ApiResponseWrapper(); }
        @Bean StoreOperationService service(Calls calls) {
            return new StoreOperationService() {
                public StoreOperationPolicyResultVO getPolicy(String entity, String store, GetPolicyQuery query) {
                assertThat(entity).isEqualTo("E");
                assertThat(store).isEqualTo("A");
                calls.count.incrementAndGet(); calls.at.set(query.at());
                return new StoreOperationPolicyResultVO("P1","Asia/Shanghai",240,"PREPAY",List.of("TABLE_CONFIG"),7);
                }
                public OperationVO createPolicy(String entity, String store, String actor,
                        CreatePolicyReq req, String key) {
                    if (!"E".equals(entity) || !"A".equals(store)) throw new AssertionError("Untrusted scope");
                    assertThat(actor).isEqualTo("staff");
                    assertThat(key).isEqualTo("original");
                    calls.count.incrementAndGet();
                    return new OperationVO("OP1", "SUCCEEDED", req.version() + 1, "P2");
                }
            };
        }
        @Bean StoreDirectoryPort stores(Calls calls) {
            return new StoreDirectoryPort() {
                public Map<String, StoreCard> cards(Collection<String> nos) {
                    return calls.owner.get() == null ? Map.of()
                            : Map.of("A",card("A",calls.owner.get()),"B",card("B",calls.owner.get()));
                }
                public List<StoreCard> reachableActive(String community, String keyword) { return List.of(); }
                public Optional<StoreCard> resolve(String no) { return Optional.empty(); }
                public Optional<StoreCard> nearestSibling(String no) { return Optional.empty(); }
                public Optional<MerchantQueryPort.StoreFront> front(String no) { return Optional.empty(); }
                public Map<String, String> statuses(Collection<String> nos) { return Map.of(); }
            };
        }
        private StoreDirectoryPort.StoreCard card(String store, String owner) {
            return new StoreDirectoryPort.StoreCard(store,store,owner,"","ACTIVE",true,
                    "22:00-02:00","",null,null,0,0);
        }
    }
}
