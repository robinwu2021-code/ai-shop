package ai.neargo.shop.food.scenario;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import ai.neargo.shop.common.PageData;
import ai.neargo.shop.food.server.api.InternalDiningBatchQueryController;
import ai.neargo.shop.food.server.config.FoodInternalSecurityConfiguration;
import ai.neargo.shop.food.service.DiningBatchQueryService;
import ai.neargo.shop.food.spi.DiningBatchQueryInternalApi;
import ai.neargo.shop.svc.InternalHttp;
import java.time.LocalDate;
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

class InternalDiningBatchQueryApiTest {
    @Test void existingServiceTokenProtectsTypedBatchFiltersAndNestedPageBounds() {
        new WebApplicationContextRunner().withUserConfiguration(Host.class).withPropertyValues("shop.food.enabled=true",
                "shop.services.internal-token=wire", ai.neargo.shop.food.scope.FoodScopeTestSigning.KEY_PROPERTY).run(c -> {
            var mvc=MockMvcBuilders.webAppContextSetup(c).addFilters(c.getBean(FilterChainProxy.class)).build();
            String request="""
                    {"entityNo":"E","storeNo":"A","actorNo":"staff","query":{"page":1,"size":20,
                    "originalCheckNo":"OLD","checkNo":"CURRENT","businessDate":"2026-10-24","status":"BOUND","orderSource":"POS"}}
                    """;
            mvc.perform(post(DiningBatchQueryInternalApi.LIST_PATH).header(InternalHttp.TOKEN_HEADER,"wire").with(ai.neargo.shop.food.scope.FoodScopeTestSigning.system())
                    .contentType(MediaType.APPLICATION_JSON).content(request)).andExpect(status().isOk())
                    .andExpect(jsonPath("$.total").value(0)).andExpect(jsonPath("$.code").doesNotExist());
            mvc.perform(post(DiningBatchQueryInternalApi.LIST_PATH).contentType(MediaType.APPLICATION_JSON).content(request))
                    .andExpect(status().isUnauthorized());
            mvc.perform(post(DiningBatchQueryInternalApi.LIST_PATH).header(InternalHttp.TOKEN_HEADER,"wire").with(ai.neargo.shop.food.scope.FoodScopeTestSigning.system())
                    .contentType(MediaType.APPLICATION_JSON).content(request.replace("\"size\":20","\"size\":101")))
                    .andExpect(status().isBadRequest());
        });
    }
    @Configuration(proxyBeanMethods=false) @EnableWebMvc
    @Import({FoodInternalSecurityConfiguration.class,InternalDiningBatchQueryController.class})
    static class Host {
        @Bean DiningBatchQueryService queries() {
            return (entity,store,actor,query) -> {
                assertThat(entity).isEqualTo("E"); assertThat(store).isEqualTo("A"); assertThat(actor).isEqualTo("staff");
                assertThat(query.checkNo()).isEqualTo("CURRENT"); assertThat(query.originalCheckNo()).isEqualTo("OLD");
                assertThat(query.businessDate()).isEqualTo(LocalDate.of(2026,10,24));
                assertThat(query.orderSource()).isEqualTo("POS");
                return PageData.of(List.of(),0,1,20);
            };
        }
    }
}
