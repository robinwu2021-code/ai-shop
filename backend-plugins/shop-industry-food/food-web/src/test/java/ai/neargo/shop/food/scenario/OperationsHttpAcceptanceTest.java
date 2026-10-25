package ai.neargo.shop.food.scenario;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import ai.neargo.common.data.scope.DataScopeSpec;
import ai.neargo.shop.auth.*;
import ai.neargo.shop.auth.store.MemoryTokenStore;
import ai.neargo.shop.common.*;
import ai.neargo.shop.food.api.FoodRequestScopeValidator;
import ai.neargo.shop.food.api.biz.*;
import ai.neargo.shop.food.api.ops.OpsFoodSupportController;
import ai.neargo.shop.food.dto.BusinessCloseContracts.*;
import ai.neargo.shop.food.dto.BusinessOperationsContracts.*;
import ai.neargo.shop.food.dto.FoodSupportContracts.ReadinessVO;
import ai.neargo.shop.food.service.*;
import ai.neargo.shop.spi.printing.PrintPort;
import ai.neargo.shop.spi.user.StoreDirectoryPort;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.context.annotation.*;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.web.*;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.test.web.servlet.*;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;

/** Public HTTP/security/serialization contracts. Owners are mocks; this is not a DB end-to-end test. */
class OperationsHttpAcceptanceTest {
    final WebApplicationContextRunner runner = new WebApplicationContextRunner()
            .withUserConfiguration(Host.class).withPropertyValues("shop.food.enabled=true", "spring.profiles.active=api,ops");

    @Test void opsReadsReadinessCasesAndOriginalSourceThroughAuthenticatedHttp() {
        runner.run(c -> {
            var service = c.getBean(FoodSupportService.class);
            when(service.readiness()).thenReturn(new ReadinessVO("standalone", false, true, "v1", List.of("PAY"), Map.of("facts", 1)));
            var source = new Source("BATCH", "B1");
            when(service.cases("E", "A", "OPEN")).thenReturn(List.of(new CaseVO("CASE1",source,"owner","OPEN","hash",null,3)));
            when(service.evidence("E","A",source)).thenReturn(new Evidence(source,"UNKNOWN",9,"hash",false,"owner"));
            var mvc = mvc(c); String token = ops(c,true);
            mvc.perform(get("/ops/x/food/support/readiness").header("Authorization",token).param("entityNo","E").param("storeNo","A"))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.code").value(0)).andExpect(jsonPath("$.data.ready").value(false))
                    .andExpect(jsonPath("$.data.missingCapabilities[0]").value("PAY"));
            mvc.perform(get("/ops/x/food/support/cases").header("Authorization",token).param("entityNo","E").param("storeNo","A").param("status","OPEN"))
                    .andExpect(jsonPath("$.data[0].caseNo").value("CASE1")).andExpect(jsonPath("$.data[0].version").value(3));
            mvc.perform(get("/ops/x/food/support/source").header("Authorization",token).param("entityNo","E").param("storeNo","A").param("kind","BATCH").param("sourceNo","B1"))
                    .andExpect(jsonPath("$.data.status").value("UNKNOWN")).andExpect(jsonPath("$.data.source.sourceNo").value("B1"));
            verify(service).cases("E","A","OPEN"); verify(service).evidence("E","A",source);
        });
    }

    @Test void opsRejectsMissingPermissionForeignMerchantForeignStoreAndMissingSource() {
        runner.run(c -> {
            var mvc = mvc(c); String token = ops(c,true);
            mvc.perform(get("/ops/x/food/support/cases").header("Authorization",ops(c,false)).param("entityNo","E").param("storeNo","A"))
                    .andExpect(jsonPath("$.code").value(10403));
            mvc.perform(get("/ops/x/food/support/cases").header("Authorization",token).param("entityNo","FOREIGN").param("storeNo","A"))
                    .andExpect(jsonPath("$.code").value(10403));
            mvc.perform(get("/ops/x/food/support/readiness").header("Authorization",token).param("entityNo","E").param("storeNo","B"))
                    .andExpect(jsonPath("$.code").value(10403));
            mvc.perform(get("/ops/x/food/support/source").header("Authorization",token).param("entityNo","E").param("storeNo","A").param("kind","BATCH"))
                    .andExpect(jsonPath("$.code").value(10400));
            mvc.perform(get("/ops/x/food/support/cases").header("Authorization",token(c,LoginUser.consumer("buyer","buyer"))).param("entityNo","E").param("storeNo","A"))
                    .andExpect(status().isUnauthorized());
            String unsupported=token(c,LoginUser.operator("limited","limited",List.of(),List.of(Perms.MERCHANT_READ),DataScopeSpec.of(ScopeDim.COMMUNITY,Set.of("COMMUNITY"))));
            mvc.perform(get("/ops/x/food/support/cases").header("Authorization",unsupported).param("entityNo","E").param("storeNo","A"))
                    .andExpect(jsonPath("$.code").value(10403));
            verifyNoInteractions(c.getBean(FoodSupportService.class));
        });
    }

    @Test void closeSignAndCarryCommandsPreserveTrustedActorOriginalHashAndVersion() {
        runner.run(c -> {
            var owner = c.getBean(BusinessCloseService.class); String hash = "a".repeat(64);
            when(owner.sign("E","A","staff",new SignReq("CL1",hash),"sign-key"))
                    .thenReturn(new CloseVO("CL1","D1","CNY",1,"SIGNED",hash,null,"staff",LocalDateTime.of(2026,10,25,10,0),null,4));
            when(owner.proposeCarry(eq("E"),eq("A"),eq("staff"),eq("CL1"),eq("EX1"),any(),eq("carry-key")))
                    .thenReturn(new ExceptionVO("EX1","CL1","SYNC","B1","PROPOSED","owner","D2","retry tomorrow","staff",null,null,null,5));
            mvc(c).perform(post("/biz/x/food/closes:sign").header("Authorization",biz(c,"staff")).header("Idempotency-Key","sign-key")
                            .contentType("application/json").content("{\"closeNo\":\"CL1\",\"snapshotHash\":\""+hash+"\"}"))
                    .andExpect(jsonPath("$.data.snapshotHash").value(hash)).andExpect(jsonPath("$.data.signedBy").value("staff"));
            mvc(c).perform(post("/biz/x/food/closes/CL1/exceptions/EX1:propose-carry").header("Authorization",biz(c,"staff")).header("Idempotency-Key","carry-key")
                            .contentType("application/json").content("{\"version\":4,\"ownerNo\":\"owner\",\"nextDayNo\":\"D2\",\"reason\":\"retry tomorrow\"}"))
                    .andExpect(jsonPath("$.data.kind").value("SYNC")).andExpect(jsonPath("$.data.status").value("PROPOSED"));
            verify(owner).proposeCarry("E","A","staff","CL1","EX1",new ProposeCarryReq(4L,"owner","D2","retry tomorrow"),"carry-key");
            when(owner.approveCarry("E","A","reviewer","CL1","EX1",new ApproveCarryReq(5L),"approve-key"))
                    .thenReturn(new ExceptionVO("EX1","CL1","SYNC","B1","CARRIED","owner","D2","retry tomorrow","staff","reviewer",LocalDateTime.of(2026,10,25,11,0),"approval",6));
            mvc(c).perform(post("/biz/x/food/closes/CL1/exceptions/EX1:approve-carry").header("Authorization",biz(c,"reviewer")).header("Idempotency-Key","approve-key")
                            .contentType("application/json").content("{\"version\":5}"))
                    .andExpect(jsonPath("$.data.status").value("CARRIED")).andExpect(jsonPath("$.data.approvedBy").value("reviewer"));
            verify(owner).approveCarry("E","A","reviewer","CL1","EX1",new ApproveCarryReq(5L),"approve-key");
        });
    }

    @Test void closeRejectsInvalidHashMissingKeyRoleAndStoreBeforeOwner() {
        runner.run(c -> {
            var mvc = mvc(c); String body = "{\"closeNo\":\"CL1\",\"snapshotHash\":\""+"a".repeat(64)+"\"}";
            mvc.perform(post("/biz/x/food/closes:sign").header("Authorization",biz(c,"staff")).header("Idempotency-Key","key").contentType("application/json").content(body.replace("a".repeat(64),"bad")))
                    .andExpect(jsonPath("$.code").value(10400));
            mvc.perform(post("/biz/x/food/closes:sign").header("Authorization",biz(c,"staff")).contentType("application/json").content(body))
                    .andExpect(jsonPath("$.code").value(10400));
            mvc.perform(post("/biz/x/food/closes:sign").header("Authorization",biz(c,"cashier")).header("Idempotency-Key","key").contentType("application/json").content(body))
                    .andExpect(jsonPath("$.code").value(70006));
            mvc.perform(post("/biz/x/food/closes:sign").header("Authorization",biz(c,"staff")).header("X-Store-No","B").header("Idempotency-Key","key").contentType("application/json").content(body))
                    .andExpect(jsonPath("$.code").value(10403));
            verifyNoInteractions(c.getBean(BusinessCloseService.class));
        });
    }

    @Test void printClaimCarriesTrustedScopeAndOriginalLeaseAndRejectsUnauthorizedStore() {
        runner.run(c -> {
            var port = c.getBean(PrintPort.class);
            when(port.claim(new PrintPort.Scope("E","A","staff"),"DEV1"))
                    .thenReturn(new PrintPort.Claim("JOB1","DEV1","ATT1","lease",LocalDateTime.of(2026,10,25,10,0),"original receipt"));
            mvc(c).perform(post("/biz/x/food/print-devices/DEV1:claim").header("Authorization",biz(c,"staff")))
                    .andExpect(jsonPath("$.data.attemptNo").value("ATT1")).andExpect(jsonPath("$.data.leaseToken").value("lease"))
                    .andExpect(jsonPath("$.data.renderedContent").value("original receipt"));
            mvc(c).perform(post("/biz/x/food/print-devices/DEV1:claim").header("Authorization",biz(c,"staff")).header("X-Store-No","B"))
                    .andExpect(jsonPath("$.code").value(10403));
            mvc(c).perform(post("/biz/x/food/print-devices/DEV1:claim").header("Authorization",biz(c,"cashier")))
                    .andExpect(jsonPath("$.code").value(70006));
            verify(port, times(1)).claim(any(),any());
        });
    }

    @Test void printBindingAndAcknowledgementPreserveBridgeAndOriginalAttemptAndOwnerDenial() {
        runner.run(c -> {
            var port=c.getBean(PrintPort.class); var scope=new PrintPort.Scope("E","A","staff");
            var req=new PrintPort.DeviceReq("DEV1","vendor","USB","secret-ref","bridge","KITCHEN",true);
            when(port.bindDevice(scope,req)).thenReturn(new PrintPort.DeviceVO("DEV1","vendor","USB","bridge","ACTIVE","KITCHEN",null,true,7));
            var ack=new PrintPort.Acknowledge("ATT1","lease","SUCCESS","channel1","OK");
            when(port.acknowledge(scope,"DEV1",ack)).thenReturn(new PrintPort.JobVO("JOB1","A","EV1","KITCHEN","DEV1","P1",1,"TPL1","SUCCESS",null,null,"staff",null,"original receipt",List.of(new PrintPort.AttemptVO("ATT1",1,"SUCCESS","OK",null,null))));
            var mvc=mvc(c); String token=biz(c,"staff");
            mvc.perform(post("/biz/x/food/print-devices").header("Authorization",token).contentType("application/json")
                            .content("{\"deviceNo\":\"DEV1\",\"vendor\":\"vendor\",\"connectionType\":\"USB\",\"credentialRef\":\"secret-ref\",\"bridgeActorNo\":\"bridge\",\"capabilities\":\"KITCHEN\",\"active\":true}"))
                    .andExpect(jsonPath("$.data.bridgeActorNo").value("bridge")).andExpect(jsonPath("$.data.authorizationVersion").value(7))
                    .andExpect(jsonPath("$.data.credentialRef").doesNotExist());
            mvc.perform(post("/biz/x/food/print-devices/DEV1:acknowledge").header("Authorization",token).contentType("application/json")
                            .content("{\"attemptNo\":\"ATT1\",\"leaseToken\":\"lease\",\"status\":\"SUCCESS\",\"channelJobNo\":\"channel1\",\"responseCode\":\"OK\"}"))
                    .andExpect(jsonPath("$.data.jobNo").value("JOB1")).andExpect(jsonPath("$.data.attempts[0].attemptNo").value("ATT1"))
                    .andExpect(jsonPath("$.data.status").value("SUCCESS"));
            when(port.heartbeat(scope,"DEV1",true)).thenThrow(BizException.of(ErrorCode.FORBIDDEN));
            mvc.perform(post("/biz/x/food/print-devices/DEV1:heartbeat").header("Authorization",token).contentType("application/json").content("{\"online\":true}"))
                    .andExpect(jsonPath("$.code").value(10403));
            verify(port).bindDevice(scope,req); verify(port).acknowledge(scope,"DEV1",ack);
        });
    }

    static MockMvc mvc(WebApplicationContext c) { return MockMvcBuilders.webAppContextSetup(c).addFilters(c.getBean(FilterChainProxy.class)).build(); }
    static String biz(WebApplicationContext c,String actor) { return token(c,LoginUser.merchant(actor,actor)); }
    static String ops(WebApplicationContext c,boolean permitted) { return token(c,LoginUser.operator("support","support",List.of(),permitted ? List.of(Perms.MERCHANT_READ,Perms.SYSTEM_INDUSTRY_READ) : List.of(),DataScopeSpec.of(ScopeDim.MERCHANT,Set.of("E")))); }
    static String token(WebApplicationContext c,LoginUser user) { return "Bearer "+c.getBean(MemoryTokenStore.class).issue(TokenStore.SessionData.of(user)); }

    @Configuration(proxyBeanMethods=false) @EnableWebMvc @EnableWebSecurity @EnableMethodSecurity
    static class Host {
        @Bean static org.springframework.validation.beanvalidation.MethodValidationPostProcessor methodValidation() { return new org.springframework.validation.beanvalidation.MethodValidationPostProcessor(); }
        @Bean MemoryTokenStore tokens() { return new MemoryTokenStore(Duration.ofMinutes(5)); }
        @Bean("perm") PermChecker perm(ObjectProvider<LivePermResolver> p) { return new PermChecker(p); }
        @Bean GlobalExceptionHandler errors() { return new GlobalExceptionHandler(); }
        @Bean ApiResponseWrapper wrapper() { return new ApiResponseWrapper(); }
        @Bean FoodRequestScopeValidator validator() { return new FoodRequestScopeValidator(); }
        @Bean FoodSupportService support() { return mock(FoodSupportService.class); }
        @Bean BusinessCloseService close() { return mock(BusinessCloseService.class); }
        @Bean PrintPort printing() { return mock(PrintPort.class); }
        @Bean StoreDirectoryPort stores() {
            var stores=mock(StoreDirectoryPort.class);
            when(stores.cards(anyCollection())).thenReturn(Map.of("A",new StoreDirectoryPort.StoreCard("A","A","E","","ACTIVE",true,"","",null,null,0,0),"B",new StoreDirectoryPort.StoreCard("B","B","FOREIGN","","ACTIVE",true,"","",null,null,0,0)));
            return stores;
        }
        @Bean OpsFoodSupportController ops(FoodSupportService s,StoreDirectoryPort d) { return new OpsFoodSupportController(s,d); }
        @Bean BizBusinessCloseController closeController(BusinessCloseService s,StoreDirectoryPort d,FoodRequestScopeValidator v) { return new BizBusinessCloseController(s,d,v); }
        @Bean BizPrintDeviceController devices(PrintPort p,StoreDirectoryPort d,FoodRequestScopeValidator v) { return new BizPrintDeviceController(p,d,v); }
        @Bean SecurityFilterChain chain(HttpSecurity http,MemoryTokenStore tokens,ObjectProvider<LiveIdentityResolver> identities) throws Exception {
            var context=new BizContextFilter((actor,requested) -> new BizContext("E",Set.of(),Set.of(),Set.of("A"),"A",false,Map.of(),Map.of("A",actor.equals("cashier") ? Set.of() : Set.of(BizPerms.FINANCE,BizPerms.VERIFY,BizPerms.RECEIVE,BizPerms.STORE_ADMIN,BizPerms.ORDER_VIEW))));
            return http.csrf(c -> c.disable()).authorizeHttpRequests(a -> a.anyRequest().authenticated())
                    .exceptionHandling(e -> e.authenticationEntryPoint((q,r,x) -> r.sendError(401)))
                    .addFilterBefore(new OperatorTokenAuthFilter(tokens,identities),UsernamePasswordAuthenticationFilter.class)
                    .addFilterBefore(new MerchantTokenAuthFilter(tokens),UsernamePasswordAuthenticationFilter.class)
                    .addFilterAfter(context,MerchantTokenAuthFilter.class).build();
        }
    }
}
