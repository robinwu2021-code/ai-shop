package ai.neargo.shop.food.scenario;

import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.food.scope.FoodScopeTestSigning;
import ai.neargo.shop.food.server.api.InternalFoodLocalStockController;
import ai.neargo.shop.food.server.api.InternalFoodStockManagementController;
import ai.neargo.shop.food.server.config.FoodInternalSecurityConfiguration;
import ai.neargo.shop.food.service.*;
import ai.neargo.shop.food.spi.FoodStockManagementInternalApi;
import ai.neargo.shop.spi.food.FoodLocalStockInternalApi;
import ai.neargo.shop.spi.food.FoodLocalStockPort;
import ai.neargo.shop.svc.InternalHttp;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.context.annotation.*;
import org.springframework.http.MediaType;
import org.springframework.security.web.FilterChainProxy;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;

class InternalFoodStockApiTest {
    private final WebApplicationContextRunner runner=new WebApplicationContextRunner().withUserConfiguration(Host.class)
            .withPropertyValues("shop.food.enabled=true","shop.food.local-stock.enabled=true",
                    "shop.services.internal-token=wire",FoodScopeTestSigning.KEY_PROPERTY);
    private static MockMvc mvc(org.springframework.web.context.WebApplicationContext c) {
        return MockMvcBuilders.webAppContextSetup(c).addFilters(c.getBean(FilterChainProxy.class)).build();
    }
    private static MockHttpServletRequestBuilder request(String path,String body) {
        return post(path).header(InternalHttp.TOKEN_HEADER,"wire").contentType(MediaType.APPLICATION_JSON).content(body);
    }
    @Test void lifecycleNestedScopeRequiresSignedEntityAndStoreAndUsesExplicitAbsence() {
        runner.run(c->{
            var service=c.getBean(FoodLocalStockPort.class);
            var mvc=mvc(c);String path=FoodLocalStockInternalApi.ROOT+"/reservation/find";
            String body="""
                    {"scope":{"entityNo":"E","storeNo":"A"},"sourceSystem":"CORE","sourceNo":"SOURCE","sourceVersion":1}
                    """;
            mvc.perform(request(path,body)).andExpect(status().isUnauthorized());
            mvc.perform(request(path,body).with(FoodScopeTestSigning.staff("E",List.of("B"),"U"))).andExpect(status().isForbidden());
            verifyNoInteractions(service);
            mvc.perform(request(path,body).with(FoodScopeTestSigning.staff("E",List.of("A"),"U")))
                    .andExpect(status().isOk()).andExpect(content().json("{\"reservation\":null}"));
            verify(service).findReservation(new FoodLocalStockPort.Scope("E","A"),"CORE","SOURCE",1);
        });
    }
    @Test void returnActorCannotBeForgedInsideNestedTargets() {
        runner.run(c->{
            String body="""
                    {"scope":{"entityNo":"E","storeNo":"A"},"command":{"operationKey":"K","requestHash":"hash",
                    "sourceSystem":"CORE","sourceNo":"SOURCE","sourceVersion":1,"lifecycleVersion":2},"reservationNo":"R",
                    "targets":[{"leafRef":"L","originalMovementNo":"MOV","targetQty":0.123456,"returnRef":"RETURN",
                    "disposition":"LOSS","reasonCode":"DAMAGED","reasonDetail":"reason","actorNo":"FORGED","requireUniformDisposition":true}]}
                    """;
            mvc(c).perform(request(FoodLocalStockInternalApi.ROOT+"/return",body)
                    .with(FoodScopeTestSigning.staff("E",List.of("A"),"U"))).andExpect(status().isForbidden());
            verifyNoInteractions(c.getBean(FoodLocalStockPort.class));
        });
    }
    @Test void managementActorIsSignedAndBusinessConflictRetainsStatus() {
        runner.run(c->{
            String body="""
                    {"entityNo":"E","storeNo":"A","actorNo":"U","itemNo":"ITEM",
                    "input":{"delta":0.123456,"version":2,"reasonCode":"COUNT","reasonDetail":"count"},"key":"K"}
                    """;
            var service=c.getBean(FoodStockBalanceService.class);
            when(service.adjust(any(),any(),any(),any(),any(),any())).thenThrow(BizException.of(ErrorCode.CONFLICT));
            var mvc=mvc(c);String path=FoodStockManagementInternalApi.ROOT+"/balances/adjust";
            mvc.perform(request(path,body).with(FoodScopeTestSigning.staff("E",List.of("A"),"OTHER")))
                    .andExpect(status().isForbidden());
            verifyNoInteractions(service);
            mvc.perform(request(path,body).with(FoodScopeTestSigning.staff("E",List.of("A"),"U")))
                    .andExpect(status().isConflict());
            verify(service).adjust(eq("E"),eq("A"),eq("ITEM"),eq("U"),any(),eq("K"));
        });
    }
    @Test void returnRecoveryRequiresTheOriginalStoreAndSignedActor() {
        runner.run(c->{
            var service=c.getBean(FoodReturnStockService.class);
            String body="""
                    {"entityNo":"E","storeNo":"A","returnNo":"RET","operationNo":"OP","actorNo":"U"}
                    """;
            String path=ai.neargo.shop.food.spi.FoodReturnStockInternalApi.ROOT+"/retry";
            var mvc=mvc(c);
            mvc.perform(request(path,body).with(FoodScopeTestSigning.staff("E",List.of("B"),"U")))
                    .andExpect(status().isForbidden());
            mvc.perform(request(path,body).with(FoodScopeTestSigning.staff("E",List.of("A"),"FORGED")))
                    .andExpect(status().isForbidden());
            verifyNoInteractions(service);
            mvc.perform(request(path,body).with(FoodScopeTestSigning.staff("E",List.of("A"),"U")))
                    .andExpect(status().isOk());
            verify(service).retry("E","A","RET","OP","U");
        });
    }
    @Test void reportFactsRequireEveryNestedStoreAndTheSignedActor() {
        runner.run(c->{
            var service=c.getBean(ai.neargo.shop.spi.food.FoodStockReportFactsPort.class);
            String body="""
                    {"partition":{"scope":{"tenantNo":"MAIN","entityNo":"E","storeNo":"A"},
                    "businessDate":"2026-10-29","metricVersion":1},
                    "day":{"dayNo":"DAY","policyNo":"P","policyVersion":0,"timezone":"UTC","cutoverMinute":0,
                    "startsAt":"2026-10-29T00:00:00Z","endsAt":"2026-10-30T00:00:00Z","kind":"FROZEN_BUSINESS_DAY"},
                    "actorNo":"U","sourceRefs":[],"rowLimit":100}
                    """;
            String path=ai.neargo.shop.food.spi.FoodStockReportInternalApi.FACTS;var mvc=mvc(c);
            mvc.perform(request(path,body).with(FoodScopeTestSigning.staff("E",List.of("B"),"U")))
                    .andExpect(status().isForbidden());
            mvc.perform(request(path,body).with(FoodScopeTestSigning.staff("E",List.of("A"),"OTHER")))
                    .andExpect(status().isForbidden());
            verifyNoInteractions(service);
            mvc.perform(request(path,body).with(FoodScopeTestSigning.staff("E",List.of("A"),"U")))
                    .andExpect(status().isOk());
            verify(service).readSnapshot(any());
        });
    }
    @Configuration(proxyBeanMethods=false) @EnableWebMvc
    @Import({FoodInternalSecurityConfiguration.class,InternalFoodLocalStockController.class,InternalFoodStockManagementController.class,
            ai.neargo.shop.food.server.api.InternalFoodReturnStockController.class,
            ai.neargo.shop.food.server.api.InternalFoodStockReportController.class})
    static class Host {
        @Bean ai.neargo.shop.spi.food.FoodStockReportFactsPort report(){return mock(ai.neargo.shop.spi.food.FoodStockReportFactsPort.class);}
        @Bean FoodReturnStockService returns(){return mock(FoodReturnStockService.class);}
        @Bean FoodLocalStockPort stock(){return mock(FoodLocalStockPort.class);}
        @Bean FoodStockItemService items(){return mock(FoodStockItemService.class);}
        @Bean FoodStockBalanceService balances(){return mock(FoodStockBalanceService.class);}
        @Bean FoodStockSyncService sync(){return mock(FoodStockSyncService.class);}
        @Bean FoodStockReconciliationService reconciliation(){return mock(FoodStockReconciliationService.class);}
    }
}
