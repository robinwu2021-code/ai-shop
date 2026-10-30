package ai.neargo.shop.food.scenario;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import ai.neargo.shop.event.outbox.DeliveryResult;
import ai.neargo.shop.food.scope.FoodScopeTestSigning;
import ai.neargo.shop.food.service.FoodStockSyncDispatchService;
import ai.neargo.shop.food.spi.FoodStockSyncInternalApi;
import ai.neargo.shop.food.server.api.InternalFoodStockSyncController;
import ai.neargo.shop.food.server.config.FoodInternalSecurityConfiguration;
import ai.neargo.shop.svc.InternalHttp;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.web.FilterChainProxy;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;

class InternalFoodStockSyncApiTest {
    private final WebApplicationContextRunner runner=new WebApplicationContextRunner().withUserConfiguration(Host.class)
            .withPropertyValues("shop.food.enabled=true","shop.food.local-stock.enabled=true","shop.services.internal-token=wire",FoodScopeTestSigning.KEY_PROPERTY);
    @Test void signedScopeAndFrameworkBindingProtectOriginalDispatch() {
        runner.run(c->{
            var mvc=MockMvcBuilders.webAppContextSetup(c).addFilters(c.getBean(FilterChainProxy.class)).build();
            var service=c.getBean(FoodStockSyncDispatchService.class);
            String body="{\"entityNo\":\"E\",\"storeNo\":\"A\",\"envelope\":{\"eventNo\":\"EV\",\"sourceSystem\":\"PLATFORM\",\"sourceEventNo\":\"EV\",\"aggregateType\":\"FOOD_STOCK_SYNC\",\"aggregateId\":\"EV\",\"eventType\":\"FOOD_STOCK_SYNC_REQUESTED\",\"schemaVersion\":1,\"streamKey\":\"ST\",\"streamSequence\":1,\"payload\":\"{}\",\"occurredAt\":\"2026-10-30T00:00:00Z\"}}";
            mvc.perform(post(FoodStockSyncInternalApi.PATH).contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isUnauthorized());
            mvc.perform(post(FoodStockSyncInternalApi.PATH).header(InternalHttp.TOKEN_HEADER,"wire").contentType(MediaType.APPLICATION_JSON).content(body)
                    .with(FoodScopeTestSigning.staff("E",List.of("OTHER"),"U"))).andExpect(status().isForbidden());
            mvc.perform(post(FoodStockSyncInternalApi.PATH).header(InternalHttp.TOKEN_HEADER,"wire").contentType(MediaType.APPLICATION_JSON).content("{}")
                    .with(FoodScopeTestSigning.system())).andExpect(status().isBadRequest());
            mvc.perform(post("/error").contentType(MediaType.APPLICATION_JSON).content("{}"))
                    .andExpect(status().isUnauthorized());
            verifyNoInteractions(service);
            when(service.dispatch(eq("E"),eq("A"),any())).thenReturn(DeliveryResult.retry("BASELINE_PENDING","cursor"));
            mvc.perform(post(FoodStockSyncInternalApi.PATH).header(InternalHttp.TOKEN_HEADER,"wire").contentType(MediaType.APPLICATION_JSON).content(body)
                    .with(FoodScopeTestSigning.system())).andExpect(status().isOk()).andExpect(jsonPath("$.kind").value("RETRYABLE"));
            verify(service).dispatch(eq("E"),eq("A"),any());
        });
    }
    @Configuration(proxyBeanMethods=false) @EnableWebMvc
    @Import({InternalFoodStockSyncController.class,FoodInternalSecurityConfiguration.class})
    static class Host {
        @Bean FoodStockSyncDispatchService service(){return mock(FoodStockSyncDispatchService.class);}
    }
}
