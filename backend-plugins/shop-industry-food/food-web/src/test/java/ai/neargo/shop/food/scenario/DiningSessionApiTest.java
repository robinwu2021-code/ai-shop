package ai.neargo.shop.food.scenario;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import ai.neargo.shop.auth.ConsumerTokenAuthFilter;
import ai.neargo.shop.auth.LoginUser;
import ai.neargo.shop.auth.TokenStore;
import ai.neargo.shop.auth.store.MemoryTokenStore;
import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.food.config.FoodWebAutoConfiguration;
import ai.neargo.shop.food.dto.DiningSessionTableVO;
import ai.neargo.shop.food.dto.DiningSessionVO;
import ai.neargo.shop.food.dto.OperationResultVO;
import ai.neargo.shop.food.dto.OperationVO;
import ai.neargo.shop.food.dto.RevokeTableTokenReq;
import ai.neargo.shop.food.dto.RotateTableTokenReq;
import ai.neargo.shop.food.dto.StartDiningSessionReq;
import ai.neargo.shop.food.dto.TableTokenVO;
import ai.neargo.shop.food.service.DiningSessionService;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.http.MediaType;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.FilterChainProxy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

class DiningSessionApiTest {
    final WebApplicationContextRunner runner=new WebApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(FoodWebAutoConfiguration.class))
            .withUserConfiguration(FoodPolicyApiTest.PolicyHost.class,FoodPolicyApiTest.PolicySecurity.class,Host.class)
            .withPropertyValues("shop.food.enabled=true","spring.profiles.active=api");
    @Test void merchantRoutesUseTrustedStoreActorAndOriginalKey() {
        runner.run(c -> {
            var mvc=mvc(c);var auth=token(c,"staff");
            mvc.perform(post("/biz/x/food/tables/T1:rotate-token").header("Authorization",auth)
                    .header("Idempotency-Key","original").contentType(MediaType.APPLICATION_JSON)
                    .content("{\"version\":0,\"expiresAt\":\"2026-12-01T00:00:00Z\"}"))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.data.token").value("only-response"));
            mvc.perform(post("/biz/x/food/tables/T1:revoke-token").header("Authorization",auth)
                    .header("Idempotency-Key","original").contentType(MediaType.APPLICATION_JSON)
                    .content("{\"version\":0,\"reason\":\"撤销\"}"))
                    .andExpect(jsonPath("$.data.operation.status").value("SUCCEEDED"));
            mvc.perform(post("/biz/x/food/tables/T1:rotate-token").header("Authorization",token(c,"cashier"))
                    .header("Idempotency-Key","original").contentType(MediaType.APPLICATION_JSON)
                    .content("{\"version\":0,\"expiresAt\":\"2026-12-01T00:00:00Z\"}"))
                    .andExpect(jsonPath("$.code").value(70006));
            mvc.perform(post("/biz/x/food/tables/T1:revoke-token").header("Authorization",auth)
                    .header("X-Store-No","OTHER").header("Idempotency-Key","original")
                    .contentType(MediaType.APPLICATION_JSON).content("{\"version\":0,\"reason\":\"撤销\"}"))
                    .andExpect(jsonPath("$.code").value(10403));
        });
    }
    @Test void anonymousBootstrapResolvesTokenBeforeDirectoryAndCannotForgeOwner() {
        runner.run(c -> {
            var mvc=mvc(c);
            mvc.perform(post("/mp/x/food/sessions").header("Idempotency-Key","original")
                    .contentType(MediaType.APPLICATION_JSON).content("{\"tableToken\":\"valid\"}"))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.data.sessionNo").value("S1"))
                    .andExpect(jsonPath("$.data.accessToken").value("short"));
            assertThat(c.getBean(Calls.class).starts.get()).isOne();
            mvc.perform(post("/mp/x/food/sessions").header("Idempotency-Key","original")
                    .contentType(MediaType.APPLICATION_JSON).content("{\"tableToken\":\"foreign\"}"))
                    .andExpect(jsonPath("$.code").value(10404));
            mvc.perform(post("/mp/x/food/sessions").header("Idempotency-Key","original")
                    .contentType(MediaType.APPLICATION_JSON).content("{\"tableToken\":\"valid\",\"entityNo\":\"E\"}"))
                    .andExpect(jsonPath("$.code").value(10400));
            assertThat(c.getBean(Calls.class).starts.get()).isOne();
        });
    }
    @Test void endRequiresDiningBearerAndDoesNotAcceptPlatformTokenAsSession() {
        runner.run(c -> {
            var mvc=mvc(c);
            mvc.perform(post("/mp/x/food/sessions/S1:end").header("Idempotency-Key","original"))
                    .andExpect(jsonPath("$.code").value(10400));
            mvc.perform(post("/mp/x/food/sessions/S1:end").header("X-Food-Session","short")
                    .header("Idempotency-Key","original"))
                    .andExpect(jsonPath("$.data.operation.status").value("SUCCEEDED"));
        });
    }
    @Test void offAndOpsDoNotExposeBusinessRoutes() {
        for(String property:new String[]{"shop.food.migration.mode=off","spring.profiles.active=ops"}) {
            runner.withPropertyValues(property).run(c -> mvc(c).perform(post("/mp/x/food/sessions")
                    .contentType(MediaType.APPLICATION_JSON).content("{}")) .andExpect(status().isNotFound()));
        }
    }
    private MockMvc mvc(WebApplicationContext c) {
        return MockMvcBuilders.webAppContextSetup(c).addFilters(c.getBean(FilterChainProxy.class)).build();
    }
    private String token(WebApplicationContext c,String actor) {
        return "Bearer "+c.getBean(MemoryTokenStore.class).issue(TokenStore.SessionData.of(LoginUser.merchant(actor,actor)));
    }
    static class Calls { final AtomicInteger starts=new AtomicInteger(); }
    @Configuration(proxyBeanMethods=false)
    static class Host {
        @Bean @Order(-1)
        SecurityFilterChain consumer(
                HttpSecurity http, MemoryTokenStore tokens)
                throws Exception {
            return http.securityMatcher("/mp/**").csrf(c -> c.disable())
                    .authorizeHttpRequests(c -> c.anyRequest().permitAll())
                    .addFilterBefore(new ConsumerTokenAuthFilter(tokens),
                            UsernamePasswordAuthenticationFilter.class)
                    .build();
        }
        @Bean Calls sessionCalls() { return new Calls(); }
        @Bean DiningSessionService diningSessionService(Calls calls) {
            return new DiningSessionService() {
                private void trusted(String e,String s,String a,String k) {
                    assertThat(e).isEqualTo("E");assertThat(s).isEqualTo("A");
                    assertThat(a).isEqualTo("staff");assertThat(k).isEqualTo("original");
                }
                public TableTokenVO rotateTableToken(String e,String s,String t,String a,RotateTableTokenReq r,String k) {
                    trusted(e,s,a,k);assertThat(t).isEqualTo("T1");return new TableTokenVO("only-response",Instant.MAX);
                }
                public OperationResultVO revokeTableToken(String e,String s,String t,String a,RevokeTableTokenReq r,String k) {
                    trusted(e,s,a,k);return operation();
                }
                public DiningSessionTableVO resolveTableToken(String t) {
                    if(!"valid".equals(t)) throw BizException.of(ErrorCode.NOT_FOUND);
                    return new DiningSessionTableVO("E","A","T1",Instant.MAX);
                }
                public DiningSessionVO startSession(String e,String s,String b,StartDiningSessionReq r,String k) {
                    assertThat(e).isEqualTo("E");assertThat(s).isEqualTo("A");assertThat(b).isNull();
                    assertThat(k).isEqualTo("original");calls.starts.incrementAndGet();
                    return new DiningSessionVO("S1","short",Instant.MAX,null);
                }
                public OperationResultVO endSession(String s,String a,String b,String k) {
                    assertThat(s).isEqualTo("S1");assertThat(a).isEqualTo("short");assertThat(b).isNull();
                    assertThat(k).isEqualTo("original");return operation();
                }
                private OperationResultVO operation() { return new OperationResultVO(new OperationVO("OP","SUCCEEDED",1L,"T1")); }
            };
        }
    }
}
