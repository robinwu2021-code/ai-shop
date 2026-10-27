package ai.neargo.shop.food.scenario;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.food.dto.MenuSourceContracts.Proof;
import ai.neargo.shop.food.dto.MenuSourceContracts.Query;
import ai.neargo.shop.food.dto.MenuSourceContracts.Resource;
import ai.neargo.shop.food.server.api.InternalConfigRevisionController;
import ai.neargo.shop.food.server.config.FoodInternalSecurityConfiguration;
import ai.neargo.shop.food.service.ConfigRevisionService;
import ai.neargo.shop.food.spi.ConfigRevisionInternalApi;
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

import java.time.OffsetDateTime;
import java.util.List;

class InternalMenuSourceApiTest {
    final WebApplicationContextRunner runner =
            new WebApplicationContextRunner()
                    .withUserConfiguration(Host.class)
                    .withPropertyValues(
                            "shop.food.enabled=true", "shop.services.internal-token=wire", ai.neargo.shop.food.scope.FoodScopeTestSigning.KEY_PROPERTY);
    static final String BODY =
            "{\"entityNo\":\"E\",\"storeNo\":\"A\",\"menuNo\":\"M\",\"revisionNo\":\"R1\","
                + "\"kind\":\"SKU\",\"resourceRef\":\"S1\",\"salesOfferRef\":\"ITEM\",\"mode\":\"CURRENT\"}";

    @Test
    void internalAuthenticationPrecedesStrictParsingAndInvalidCommandsNeverReachTheOwner() {
        runner.run(
                c -> {
                    var service = c.getBean(ConfigRevisionService.class);
                    var mvc =
                            MockMvcBuilders.webAppContextSetup(c)
                                    .addFilters(c.getBean(FilterChainProxy.class))
                                    .build();
                    mvc.perform(
                                    post(ConfigRevisionInternalApi.MENU_SOURCE_PATH)
                                            .contentType(MediaType.APPLICATION_JSON)
                                            .content("{"))
                            .andExpect(status().isUnauthorized());
                    for (String invalid :
                            List.of(
                                    BODY.replace("\"E\"", "12"),
                                    BODY.replace("\"kind\":\"SKU\"", "\"kind\":3"),
                                    BODY.replace("\"mode\":\"CURRENT\"", "\"mode\":0"),
                                    BODY.replace("\"CURRENT\"", "\"LATEST\""),
                                    BODY.replace(",\"salesOfferRef\":\"ITEM\"", ""),
                                    BODY.replace("\"SKU\"", "\"OPTION\""),
                                    BODY.substring(0, BODY.length() - 1) + ",\"priceMinor\":1}")) {
                        mvc.perform(
                                        post(ConfigRevisionInternalApi.MENU_SOURCE_PATH)
                                                .header(InternalHttp.TOKEN_HEADER, "wire").with(ai.neargo.shop.food.scope.FoodScopeTestSigning.system())
                                                .contentType(MediaType.APPLICATION_JSON)
                                                .content(invalid))
                                .andExpect(status().isBadRequest());
                    }
                    verifyNoInteractions(service);
                });
    }

    @Test
    void delegatesExactQueryAndPreservesConflictInsteadOfReturningASuccessfulProof() {
        runner.run(
                c -> {
                    var service = c.getBean(ConfigRevisionService.class);
                    var mvc =
                            MockMvcBuilders.webAppContextSetup(c)
                                    .addFilters(c.getBean(FilterChainProxy.class))
                                    .build();
                    var at = OffsetDateTime.parse("2026-10-26T08:00:00Z");
                    when(service.verifyMenuResource(any()))
                            .thenAnswer(
                                    call -> {
                                        Query q = call.getArgument(0);
                                        assertThat(q.entityNo()).isEqualTo("E");
                                        assertThat(q.storeNo()).isEqualTo("A");
                                        if ("OLD".equals(q.revisionNo()))
                                            throw BizException.of(ErrorCode.CONFLICT);
                                        return new Proof(
                                                1,
                                                q,
                                                null,
                                                new Resource(
                                                        q.kind(),
                                                        q.resourceRef(),
                                                        q.salesOfferRef(),
                                                        null),
                                                at,
                                                at.plusSeconds(30),
                                                "test-only");
                                    });
                    mvc.perform(
                                    post(ConfigRevisionInternalApi.MENU_SOURCE_PATH)
                                            .header(InternalHttp.TOKEN_HEADER, "wire").with(ai.neargo.shop.food.scope.FoodScopeTestSigning.system())
                                            .contentType(MediaType.APPLICATION_JSON)
                                            .content(BODY))
                            .andExpect(status().isOk())
                            .andExpect(jsonPath("$.resource.salesOfferRef").value("ITEM"))
                            .andExpect(jsonPath("$.schemaVersion").value(1));
                    mvc.perform(
                                    post(ConfigRevisionInternalApi.MENU_SOURCE_PATH)
                                            .header(InternalHttp.TOKEN_HEADER, "wire").with(ai.neargo.shop.food.scope.FoodScopeTestSigning.system())
                                            .contentType(MediaType.APPLICATION_JSON)
                                            .content(BODY.replace("R1", "OLD")))
                            .andExpect(status().isConflict());
                });
    }

    @Configuration(proxyBeanMethods = false)
    @EnableWebMvc
    @Import({FoodInternalSecurityConfiguration.class, InternalConfigRevisionController.class})
    static class Host {
        @Bean
        ConfigRevisionService revisions() {
            return mock(ConfigRevisionService.class);
        }
    }
}
