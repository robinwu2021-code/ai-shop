package ai.neargo.shop.food.scenario;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.food.dto.StoreCalendarSourceContracts.Facts;
import ai.neargo.shop.food.dto.StoreCalendarSourceContracts.Query;
import ai.neargo.shop.food.server.api.InternalStoreCalendarSourceController;
import ai.neargo.shop.food.server.config.FoodInternalSecurityConfiguration;
import ai.neargo.shop.food.service.StoreCalendarSourceService;
import ai.neargo.shop.food.spi.StoreCalendarSourceInternalApi;
import ai.neargo.shop.svc.InternalHttp;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.web.FilterChainProxy;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

class InternalStoreCalendarSourceApiTest {
    final WebApplicationContextRunner runner =
            new WebApplicationContextRunner()
                    .withUserConfiguration(Host.class)
                    .withPropertyValues(
                            "shop.food.enabled=true", "shop.services.internal-token=wire", ai.neargo.shop.food.scope.FoodScopeTestSigning.KEY_PROPERTY);
    static final String BODY =
            "{\"entityNo\":\"E\",\"storeNo\":\"A\",\"fromDate\":\"2026-10-20\",\"toDate\":\"2026-10-21\"}";

    @Test
    void tokenAuthenticationPrecedesParsingAndCallersCannotInjectPricesOrForeignFacts() {
        runner.run(
                c -> {
                    assertThat(c).hasNotFailed();
                    var service = c.getBean(StoreCalendarSourceService.class);
                    var mvc =
                            MockMvcBuilders.webAppContextSetup(c)
                                    .addFilters(c.getBean(FilterChainProxy.class))
                                    .build();
                    mvc.perform(
                                    post(StoreCalendarSourceInternalApi.CAPTURE_PATH)
                                            .contentType(MediaType.APPLICATION_JSON)
                                            .content("{"))
                            .andExpect(status().isUnauthorized());
                    for (String bad :
                            List.of(
                                    BODY.replace("\"E\"", "12"),
                                    BODY.replace("\"A\"", "\" A\""),
                                    BODY.substring(0, BODY.length() - 1) + ",\"priceMinor\":1}",
                                    BODY.replace("\"2026-10-20\"", "[2026,10,20]"),
                                    "{}"))
                        mvc.perform(
                                        post(StoreCalendarSourceInternalApi.CAPTURE_PATH)
                                                .header(InternalHttp.TOKEN_HEADER, "wire").with(ai.neargo.shop.food.scope.FoodScopeTestSigning.system())
                                                .contentType(MediaType.APPLICATION_JSON)
                                                .content(bad))
                                .andExpect(status().isBadRequest());
                    verifyNoInteractions(service);
                });
    }

    @Test
    void typedResponseIsUnwrappedAndBusinessFailureStatusIsPreserved() {
        runner.run(
                c -> {
                    var service = c.getBean(StoreCalendarSourceService.class);
                    var query =
                            new Query(
                                    "E",
                                    "A",
                                    LocalDate.of(2026, 10, 20),
                                    LocalDate.of(2026, 10, 21));
                    var mvc =
                            MockMvcBuilders.webAppContextSetup(c)
                                    .addFilters(c.getBean(FilterChainProxy.class))
                                    .build();
                    // Endpoint delegation uses a stub, not a fabricated assertion of original
                    // evidence validity.
                    when(service.capture(query))
                            .thenReturn(
                                    new Facts(
                                            1,
                                            "STORE_CALENDAR",
                                            query,
                                            null,
                                            List.of(),
                                            List.of(),
                                            null,
                                            null,
                                            "receipt"));
                    mvc.perform(
                                    post(StoreCalendarSourceInternalApi.CAPTURE_PATH)
                                            .header(InternalHttp.TOKEN_HEADER, "wire").with(ai.neargo.shop.food.scope.FoodScopeTestSigning.system())
                                            .contentType(MediaType.APPLICATION_JSON)
                                            .content(BODY))
                            .andExpect(status().isOk())
                            .andExpect(jsonPath("$.purpose").value("STORE_CALENDAR"))
                            .andExpect(jsonPath("$.data").doesNotExist());
                    for (var expected :
                            Map.of(
                                            ErrorCode.BAD_REQUEST,
                                            400,
                                            ErrorCode.FORBIDDEN,
                                            403,
                                            ErrorCode.NOT_FOUND,
                                            404,
                                            ErrorCode.CONFLICT,
                                            409)
                                    .entrySet()) {
                        doThrow(BizException.of(expected.getKey())).when(service).capture(query);
                        mvc.perform(
                                        post(StoreCalendarSourceInternalApi.CAPTURE_PATH)
                                                .header(InternalHttp.TOKEN_HEADER, "wire").with(ai.neargo.shop.food.scope.FoodScopeTestSigning.system())
                                                .contentType(MediaType.APPLICATION_JSON)
                                                .content(BODY))
                                .andExpect(status().is(expected.getValue()));
                    }
                });
    }

    @Configuration(proxyBeanMethods = false)
    @EnableWebMvc
    @Import({FoodInternalSecurityConfiguration.class, InternalStoreCalendarSourceController.class})
    static class Host {
        @Bean
        StoreCalendarSourceService source() {
            return mock(StoreCalendarSourceService.class);
        }
    }
}
