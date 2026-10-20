package ai.neargo.shop.food.scenario;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import ai.neargo.shop.auth.BizContext;
import ai.neargo.shop.auth.BizContextFilter;
import ai.neargo.shop.auth.LoginUser;
import ai.neargo.shop.auth.LivePermResolver;
import ai.neargo.shop.auth.MerchantTokenAuthFilter;
import ai.neargo.shop.auth.PermChecker;
import ai.neargo.shop.auth.SecurityUtils;
import ai.neargo.shop.auth.TokenStore;
import ai.neargo.shop.auth.store.MemoryTokenStore;
import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.food.api.FoodRequestScopeValidator;
import ai.neargo.shop.food.config.FoodWebAutoConfiguration;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.context.ApplicationContext;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.web.FilterChainProxy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;

class FoodStoreScopeTest {
    private final FoodRequestScopeValidator validator = new FoodRequestScopeValidator();
    private final WebApplicationContextRunner runner = new WebApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(FoodWebAutoConfiguration.class))
            .withUserConfiguration(Host.class)
            .withPropertyValues("shop.food.enabled=true", "spring.profiles.active=api");

    @Test void registersScopeGuardInEnabledWebOnly() {
        new WebApplicationContextRunner().withConfiguration(AutoConfigurations.of(FoodWebAutoConfiguration.class))
                .withPropertyValues("shop.food.enabled=true", "spring.profiles.active=api")
                .run(context -> assertThat(context).hasNotFailed().hasSingleBean(FoodRequestScopeValidator.class));
    }
    @Test void rejectCrossEntityResource() {
        forbidden(() -> validator.requireStoreTarget("E", "A", "FOREIGN", "A", "A", Set.of("A")));
    }
    @Test void rejectDifferentStoreEvenWhenBothAreAuthorized() {
        forbidden(() -> validator.requireStoreTarget("E", "A", "E", "B", "B", Set.of("A", "B")));
    }
    @Test void rejectUnassignedCurrentStore() {
        forbidden(() -> validator.requireStoreTarget("E", "A", "E", "A", null, Set.of("B")));
        forbidden(() -> validator.requireStoreTarget("E", "A", "E", "A", null, null));
    }
    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" ", "\t"})
    void rejectMissingTrustedFacts(String invalid) {
        forbidden(() -> validator.requireStoreTarget(invalid, "A", "E", "A", null, Set.of("A")));
        forbidden(() -> validator.requireStoreTarget("E", invalid, "E", "A", null, Set.of("A")));
        forbidden(() -> validator.requireStoreTarget("E", "A", invalid, "A", null, Set.of("A")));
        forbidden(() -> validator.requireStoreTarget("E", "A", "E", invalid, null, Set.of("A")));
    }
    @Test void explicitBlankHeaderIsNotAnImplicitStoreSwitch() {
        forbidden(() -> validator.requireStoreTarget("E", "A", "E", "A", " ", Set.of("A")));
    }
    @Test void rejectInvalidHeaderAfterDefaultFallback() {
        runner.run(context -> {
            assertThat(context).hasNotFailed();
            var mvc = mvc(context);
            String token = token(context, "staff");
            mvc.perform(post("/biz/food-test-scope/A").header("Authorization", "Bearer " + token)
                            .header(BizContextFilter.STORE_HEADER, "UNAUTHORIZED")
                            .contentType("application/json").content("{}"))
                    .andExpect(status().isForbidden());
            assertThat(context.getBean(Recorder.class).writes).isEmpty();
            assertThat(BizContext.current()).isEqualTo(BizContext.NONE);
        });
    }
    @Test void ignoreSpoofedActor() {
        runner.run(context -> {
            mvc(context).perform(post("/biz/food-test-scope/A")
                            .header("Authorization", "Bearer " + token(context, "staff"))
                            .contentType("application/json")
                            .content("{\"entityNo\":\"FOREIGN\",\"storeNo\":\"FOREIGN\",\"actorNo\":\"spoof\"}"))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.actorNo").value("staff"))
                    .andExpect(jsonPath("$.entityNo").value("E")).andExpect(jsonPath("$.storeNo").value("A"));
            assertThat(context.getBean(Recorder.class).writes).hasSize(1);
        });
    }
    @Test void denyMoneyActionWithoutPermission() {
        runner.run(context -> {
            mvc(context).perform(post("/biz/food-test-scope/A")
                            .header("Authorization", "Bearer " + token(context, "cashier"))
                            .contentType("application/json").content("{}"))
                    .andExpect(status().isForbidden());
            assertThat(context.getBean(Recorder.class).writes).isEmpty();
        });
    }
    @Test void validStoreSwitchUsesResolvedPermissionsAndResourceFacts() {
        runner.run(context -> {
            mvc(context).perform(post("/biz/food-test-scope/B")
                            .header("Authorization", "Bearer " + token(context, "staff"))
                            .header(BizContextFilter.STORE_HEADER, "B").contentType("application/json").content("{}"))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.storeNo").value("B"));
        });
    }
    private static void forbidden(Runnable action) {
        assertThatThrownBy(action::run).isInstanceOfSatisfying(BizException.class,
                error -> assertThat(error.errorCode()).isEqualTo(ErrorCode.FORBIDDEN));
    }
    private String token(ApplicationContext context, String actor) {
        return context.getBean(MemoryTokenStore.class)
                .issue(TokenStore.SessionData.of(LoginUser.merchant(actor, actor)));
    }
    private MockMvc mvc(WebApplicationContext context) {
        return MockMvcBuilders.webAppContextSetup(context).addFilters(context.getBean(FilterChainProxy.class)).build();
    }
    static class Recorder { final List<Map<String, String>> writes = new ArrayList<>(); }
    record UntrustedBody(String entityNo, String storeNo, String actorNo) { }

    /** Test-only ingress probe, not a business endpoint and never packaged. */
    @RestController
    public static class Probe {
        private final FoodRequestScopeValidator scope;
        private final Recorder recorder;
        Probe(FoodRequestScopeValidator scope, Recorder recorder) { this.scope = scope; this.recorder = recorder; }
        @PostMapping("/biz/food-test-scope/{resource}")
        @PreAuthorize("@perm.canBiz('biz:finance')")
        public Map<String, String> write(@PathVariable String resource, @RequestBody UntrustedBody ignored,
                @RequestHeader(value = BizContextFilter.STORE_HEADER, required = false) String header) {
            BizContext context = BizContext.current();
            // This fixed test catalog represents facts read by an actual resource/directory lookup.
            if (!Set.of("A", "B").contains(resource)) throw BizException.of(ErrorCode.NOT_FOUND);
            scope.requireStoreTarget(context.merchantNo(), context.currentStoreNo(), "E", resource,
                    header, context.storeNos());
            var write = Map.of("actorNo", SecurityUtils.currentUserNo(), "entityNo", context.merchantNo(),
                    "storeNo", context.currentStoreNo());
            recorder.writes.add(write);
            return write;
        }
    }
    @RestControllerAdvice
    static class Errors {
        @ExceptionHandler(BizException.class)
        ResponseEntity<Void> forbidden(BizException error) { return ResponseEntity.status(403).build(); }
    }
    @Configuration(proxyBeanMethods = false)
    @EnableWebMvc
    @EnableWebSecurity
    @EnableMethodSecurity
    static class Host {
        @Bean MemoryTokenStore tokens() { return new MemoryTokenStore(Duration.ofMinutes(5)); }
        @Bean Recorder recorder() { return new Recorder(); }
        @Bean Probe probe(FoodRequestScopeValidator guard, Recorder recorder) { return new Probe(guard, recorder); }
        @Bean Errors errors() { return new Errors(); }
        @Bean("perm") PermChecker perm(ObjectProvider<LivePermResolver> resolver) {
            return new PermChecker(resolver);
        }
        @Bean SecurityFilterChain chain(HttpSecurity http, MemoryTokenStore tokens) throws Exception {
            var context = new BizContextFilter((actor, requested) -> new BizContext("E", Set.of(), Set.of(),
                    Set.of("A", "B"), "A", false, Map.of(), Map.of(
                            "A", actor.equals("cashier") ? Set.of() : Set.of("biz:finance", "biz:store"),
                            "B", Set.of("biz:finance", "biz:store"))));
            return http.csrf(csrf -> csrf.disable()).authorizeHttpRequests(auth -> auth.anyRequest().authenticated())
                    .exceptionHandling(errors -> errors.authenticationEntryPoint((req, res, ex) -> res.sendError(401)))
                    .addFilterBefore(new MerchantTokenAuthFilter(tokens), UsernamePasswordAuthenticationFilter.class)
                    .addFilterAfter(context, MerchantTokenAuthFilter.class).build();
        }
    }
}
