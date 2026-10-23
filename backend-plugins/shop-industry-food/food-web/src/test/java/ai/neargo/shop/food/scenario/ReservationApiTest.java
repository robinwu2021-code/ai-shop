package ai.neargo.shop.food.scenario;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import ai.neargo.shop.common.PageData;
import ai.neargo.shop.food.config.FoodWebAutoConfiguration;
import ai.neargo.shop.food.dto.ReservationVO;
import ai.neargo.shop.food.service.ReservationService;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.web.context.WebApplicationContext;

class ReservationApiTest {
    final WebApplicationContextRunner runner=new WebApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(FoodWebAutoConfiguration.class))
            .withUserConfiguration(FoodPolicyApiTest.PolicyHost.class,CheckApiTest.Security.class)
            .withBean(ReservationService.class,() -> (entity,store,query) -> {
                assertThat(entity).isEqualTo("E");assertThat(store).isEqualTo("A");
                assertThat(query.from()).isEqualTo(Instant.parse("2026-10-21T12:00:00Z"));
                return PageData.of(List.of(new ReservationVO("R1",2,query.from(),query.to(),"CONFIRMED",null,null,0L)),1,1,20);
            }).withPropertyValues("shop.food.enabled=true","spring.profiles.active=api");
    @Test void queryUsesOriginalStorePermissionsAndTypedDates() {
        runner.run(c -> {
            var helper=new CheckApiTest();var mvc=helper.mvc(c);var auth=helper.auth(c,"staff");
            var request=get("/biz/x/food/stores/A/reservations").header("Authorization",auth)
                    .param("from","2026-10-21T12:00:00Z").param("to","2026-10-21T14:00:00Z");
            mvc.perform(request).andExpect(status().isOk()).andExpect(jsonPath("$.data.records[0].reservationNo").value("R1"));
            mvc.perform(get("/biz/x/food/stores/A/reservations").header("Authorization",auth)).andExpect(jsonPath("$.code").value(10400));
            mvc.perform(get("/biz/x/food/stores/A/reservations").header("Authorization",helper.auth(c,"denied"))
                    .param("from","2026-10-21T12:00:00Z").param("to","2026-10-21T14:00:00Z"))
                    .andExpect(jsonPath("$.code").value(70006));
            mvc.perform(get("/biz/x/food/stores/A/reservations").header("Authorization",auth).header("X-Store-No","B")
                    .param("from","2026-10-21T12:00:00Z").param("to","2026-10-21T14:00:00Z"))
                    .andExpect(jsonPath("$.code").value(10403));
        });
    }
    @Test void offAndOpsDoNotExposeReservations() {
        for(String setting:List.of("shop.food.migration.mode=off","spring.profiles.active=ops")) runner.withPropertyValues(setting)
                .run(c -> mvc404(c));
    }
    void mvc404(WebApplicationContext c) throws Exception {
        var helper=new CheckApiTest();helper.mvc(c).perform(get("/biz/x/food/stores/A/reservations")
                .header("Authorization",helper.auth(c,"staff"))).andExpect(status().isNotFound());
    }
}
