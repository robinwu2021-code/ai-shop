package ai.neargo.shop.food.scenario;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import ai.neargo.shop.common.PageData;
import ai.neargo.shop.food.dto.ReservationVO;
import ai.neargo.shop.food.server.api.InternalReservationController;
import ai.neargo.shop.food.server.config.FoodInternalSecurityConfiguration;
import ai.neargo.shop.food.service.ReservationService;
import ai.neargo.shop.food.spi.ReservationInternalApi;
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

class InternalReservationApiTest {
    @Test void originalServiceTokenAndNestedValidationGuardQuery() {
        new WebApplicationContextRunner().withUserConfiguration(Host.class).withPropertyValues("shop.food.enabled=true",
                "shop.services.internal-token=wire").run(c -> {
                    var mvc=MockMvcBuilders.webAppContextSetup(c).addFilters(c.getBean(FilterChainProxy.class)).build();
                    String body="{\"entityNo\":\"E\",\"storeNo\":\"A\",\"query\":{\"from\":\"2026-10-21T12:00:00Z\",\"to\":\"2026-10-21T14:00:00Z\"}}";
                    mvc.perform(post(ReservationInternalApi.LIST_PATH).header(InternalHttp.TOKEN_HEADER,"wire")
                            .contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isOk())
                            .andExpect(jsonPath("$.records[0].reservationNo").value("R1")).andExpect(jsonPath("$.code").doesNotExist());
                    mvc.perform(post(ReservationInternalApi.LIST_PATH).contentType(MediaType.APPLICATION_JSON).content(body))
                            .andExpect(status().isUnauthorized());
                    mvc.perform(post(ReservationInternalApi.LIST_PATH).header(InternalHttp.TOKEN_HEADER,"wire")
                            .contentType(MediaType.APPLICATION_JSON).content("{\"entityNo\":\"E\",\"storeNo\":\"A\",\"query\":{}}"))
                            .andExpect(status().isBadRequest());
                });
    }
    @Configuration(proxyBeanMethods=false) @EnableWebMvc
    @Import({FoodInternalSecurityConfiguration.class, InternalReservationController.class})
    static class Host {
        @Bean ReservationService service() { return (entity,store,query) -> {
            assertThat(entity).isEqualTo("E");assertThat(store).isEqualTo("A");
            return PageData.of(List.of(new ReservationVO("R1",2,query.from(),query.to(),"CONFIRMED",null,null,0L)),1,1,20);
        }; }
    }
}
