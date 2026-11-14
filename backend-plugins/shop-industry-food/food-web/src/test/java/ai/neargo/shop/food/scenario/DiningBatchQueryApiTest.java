package ai.neargo.shop.food.scenario;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import ai.neargo.shop.common.PageData;
import ai.neargo.shop.food.api.biz.BizDiningBatchQueryController;
import ai.neargo.shop.food.config.FoodWebAutoConfiguration;
import ai.neargo.shop.food.service.DiningBatchQueryService;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

class DiningBatchQueryApiTest {
    final WebApplicationContextRunner runner=new WebApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(FoodWebAutoConfiguration.class))
            .withUserConfiguration(FoodPolicyApiTest.PolicyHost.class,CheckApiTest.Security.class,Host.class)
            .withPropertyValues("shop.food.enabled=true","spring.profiles.active=api");
    @Test void listingUsesOriginalStoreReadPermissionAndTypedCurrentOriginalFilters() {
        runner.run(c -> {
            var helper=new CheckApiTest(); var mvc=helper.mvc(c); var auth=helper.auth(c,"staff");
            mvc.perform(get("/biz/x/food/stores/A/batches").header("Authorization",auth)
                    .param("originalCheckNo","OLD").param("checkNo","CURRENT").param("businessDate","2026-10-24")
                    .param("status","BOUND").param("orderSource","POS").param("actorNo","FORGED"))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.data.total").value(0));
            mvc.perform(get("/biz/x/food/stores/A/batches").header("Authorization",helper.auth(c,"denied")))
                    .andExpect(jsonPath("$.code").value(70006));
            mvc.perform(get("/biz/x/food/stores/A/batches").header("Authorization",auth).header("X-Store-No","B"))
                    .andExpect(jsonPath("$.code").value(10403));
            mvc.perform(get("/biz/x/food/stores/A/batches").header("Authorization",auth).param("size","101"))
                    .andExpect(jsonPath("$.code").value(10400));
        });
    }
    @Test void disabledAndOpsDoNotExposeBusinessBatchListing() {
        for(String setting:List.of("shop.food.migration.mode=off","spring.profiles.active=ops"))
            runner.withPropertyValues(setting).run(c -> {
                var helper=new CheckApiTest(); helper.mvc(c).perform(get("/biz/x/food/stores/A/batches")
                        .header("Authorization",helper.auth(c,"staff"))).andExpect(status().isNotFound());
            });
    }
    @Configuration(proxyBeanMethods=false) @Import(BizDiningBatchQueryController.class)
    static class Host {
        @Bean DiningBatchQueryService batchQueries() {
            return (entity,store,actor,query) -> {
                assertThat(entity).isEqualTo("E"); assertThat(store).isEqualTo("A"); assertThat(actor).isEqualTo("staff");
                assertThat(query.originalCheckNo()).isEqualTo("OLD"); assertThat(query.checkNo()).isEqualTo("CURRENT");
                assertThat(query.businessDate()).isEqualTo(LocalDate.of(2026,10,24));
                assertThat(query.status()).isEqualTo("BOUND"); assertThat(query.orderSource()).isEqualTo("POS");
                return PageData.of(List.of(),0,1,20);
            };
        }
    }
}
