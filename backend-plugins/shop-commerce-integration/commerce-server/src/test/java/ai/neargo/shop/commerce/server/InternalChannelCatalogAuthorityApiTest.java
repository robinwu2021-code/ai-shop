package ai.neargo.shop.commerce.server;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import ai.neargo.shop.commerce.api.internal.InternalChannelCatalogAuthorityController;
import ai.neargo.shop.commerce.config.CommerceInternalAutoConfiguration;
import ai.neargo.shop.commerce.config.CommerceWebAutoConfiguration;
import ai.neargo.shop.commerce.dto.ChannelCatalogAuthorityContracts.Facts;
import ai.neargo.shop.commerce.dto.ChannelCatalogAuthorityContracts.Query;
import ai.neargo.shop.commerce.server.config.CommerceInternalSecurityConfiguration;
import ai.neargo.shop.commerce.service.ChannelCatalogAuthorityService;
import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.spi.commerce.ChannelCatalogAuthorityInternalApi;
import ai.neargo.shop.svc.InternalHttp;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.web.FilterChainProxy;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;

import java.util.List;
import java.util.Map;

class InternalChannelCatalogAuthorityApiTest {
    final WebApplicationContextRunner runner =
            new WebApplicationContextRunner()
                    .withUserConfiguration(Host.class)
                    .withPropertyValues(
                            "shop.commerce.enabled=true",
                            "shop.commerce.deployment=standalone",
                            "shop.services.internal-token=wire");
    static final String BODY =
            "{\"entityNo\":\"E\",\"storeNo\":\"A\",\"connectionNo\":\"M\",\"revisionNo\":\"R1\"}";

    @Test
    void tokenAuthenticationPrecedesParsingAndCallersCannotInjectPricesOrForeignFacts() {
        runner.run(
                c -> {
                    assertThat(c).hasNotFailed();
                    var service = c.getBean(ChannelCatalogAuthorityService.class);
                    var mvc =
                            MockMvcBuilders.webAppContextSetup(c)
                                    .addFilters(c.getBean(FilterChainProxy.class))
                                    .build();
                    mvc.perform(
                                    post(ChannelCatalogAuthorityInternalApi.CAPTURE_PATH)
                                            .contentType(MediaType.APPLICATION_JSON)
                                            .content("{"))
                            .andExpect(status().isUnauthorized());
                    for (String bad :
                            List.of(
                                    BODY.replace("\"E\"", "12"),
                                    BODY.replace("\"A\"", "\" A\""),
                                    BODY.substring(0, BODY.length() - 1) + ",\"priceMinor\":1}",
                                    "{}"))
                        mvc.perform(
                                        post(ChannelCatalogAuthorityInternalApi.CAPTURE_PATH)
                                                .header(InternalHttp.TOKEN_HEADER, "wire")
                                                .contentType(MediaType.APPLICATION_JSON)
                                                .content(bad))
                                .andExpect(status().isBadRequest());
                    mvc.perform(
                                    get("/biz/channel/providers")
                                            .header(InternalHttp.TOKEN_HEADER, "wire"))
                            .andExpect(status().isUnauthorized());
                    verifyNoInteractions(service);
                });
    }

    @Test
    void typedResponseIsUnwrappedAndBusinessFailureStatusIsPreserved() {
        runner.run(
                c -> {
                    var service = c.getBean(ChannelCatalogAuthorityService.class);
                    var query = new Query("E", "A", "M", "R1");
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
                                            "CATALOG_AUTHORITY",
                                            query,
                                            null,
                                            null,
                                            null,
                                            null,
                                            null,
                                            null,
                                            null,
                                            null,
                                            "receipt"));
                    mvc.perform(
                                    post(ChannelCatalogAuthorityInternalApi.CAPTURE_PATH)
                                            .header(InternalHttp.TOKEN_HEADER, "wire")
                                            .contentType(MediaType.APPLICATION_JSON)
                                            .content(BODY))
                            .andExpect(status().isOk())
                            .andExpect(jsonPath("$.purpose").value("CATALOG_AUTHORITY"))
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
                                        post(ChannelCatalogAuthorityInternalApi.CAPTURE_PATH)
                                                .header(InternalHttp.TOKEN_HEADER, "wire")
                                                .contentType(MediaType.APPLICATION_JSON)
                                                .content(BODY))
                                .andExpect(status().is(expected.getValue()));
                    }
                });
    }

    @Test
    void internalAutoConfigurationKeepsIndependentPublicControllersClosed() {
        var original = mock(ChannelCatalogAuthorityService.class);
        var contexts =
                new ApplicationContextRunner()
                        .withConfiguration(
                                AutoConfigurations.of(
                                        CommerceInternalAutoConfiguration.class,
                                        CommerceWebAutoConfiguration.class))
                        .withBean(ChannelCatalogAuthorityService.class, () -> original);
        contexts.run(
                c ->
                        assertThat(c)
                                .doesNotHaveBean(InternalChannelCatalogAuthorityController.class));
        contexts.withPropertyValues(
                        "shop.commerce.enabled=true",
                        "shop.commerce.deployment=standalone",
                        "spring.profiles.active=api")
                .run(
                        c -> {
                            assertThat(c).hasNotFailed();
                            assertThat(c)
                                    .hasSingleBean(InternalChannelCatalogAuthorityController.class);
                            assertThat(c).doesNotHaveBean("bizChannelProviderController");
                        });
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(CommerceInternalAutoConfiguration.class))
                .withPropertyValues("shop.commerce.enabled=true")
                .run(
                        c ->
                                assertThat(c)
                                        .doesNotHaveBean(
                                                InternalChannelCatalogAuthorityController.class));
    }

    @Configuration(proxyBeanMethods = false)
    @EnableWebMvc
    @Import({
        CommerceInternalSecurityConfiguration.class,
        InternalChannelCatalogAuthorityController.class
    })
    static class Host {
        @Bean
        ChannelCatalogAuthorityService source() {
            return mock(ChannelCatalogAuthorityService.class);
        }
    }
}
