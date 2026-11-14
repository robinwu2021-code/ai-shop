package ai.neargo.shop.scenario;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import ai.neargo.shop.channel.media.MediaDeliveryConfig;
import ai.neargo.shop.common.ApiResponseWrapper;
import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.common.GlobalExceptionHandler;
import ai.neargo.shop.portal.internal.InternalMediaPublicSourceController;
import ai.neargo.shop.spi.media.MediaPublicSourceFacts;
import ai.neargo.shop.spi.media.MediaPublicSourceInternalApi;
import ai.neargo.shop.spi.media.MediaPublicSourcePort;
import ai.neargo.shop.spi.media.MediaPublicSourcePort.Asset;
import ai.neargo.shop.spi.media.MediaPublicSourcePort.Facts;
import ai.neargo.shop.spi.media.MediaPublicSourcePort.Request;
import ai.neargo.shop.svc.InternalHttp;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.util.TestPropertyValues;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import tools.jackson.databind.json.JsonMapper;

import java.time.Instant;
import java.util.List;
import java.util.Map;

class InternalMediaPublicSourceApiTest {
    static final String KEY = "E/S/goods/a.jpg";
    static final Request REQUEST = new Request("E", "S", List.of(KEY));
    static final String BODY =
            "{\"entityNo\":\"E\",\"storeNo\":\"S\",\"assetKeys\":[\"E/S/goods/a.jpg\"]}";

    @Test
    void serviceTokenPrecedesMalformedBodyAndStrictRequestValidation() throws Exception {
        var port = mock(MediaPublicSourcePort.class);
        var mvc =
                MockMvcBuilders.standaloneSetup(
                                new InternalMediaPublicSourceController(port, "secret"))
                        .setControllerAdvice(new GlobalExceptionHandler(), new ApiResponseWrapper())
                        .build();
        for (var input : List.of(BODY, "{", "{}", "null", ""))
            mvc.perform(
                            post(MediaPublicSourceInternalApi.CAPTURE_PATH)
                                    .contentType("application/json")
                                    .content(input))
                    .andExpect(status().isUnauthorized());
        for (var input :
                List.of(
                        "{}",
                        "{",
                        "null",
                        BODY.replace("\"E\"", "12"),
                        BODY.replace("\"assetKeys\"", "\"url\":\"https://foreign/\",\"assetKeys\""),
                        BODY.replace("\"E/S/goods/a.jpg\"", "42"),
                        BODY.replace("a.jpg", "../a.jpg")))
            mvc.perform(
                            post(MediaPublicSourceInternalApi.CAPTURE_PATH)
                                    .header(InternalHttp.TOKEN_HEADER, "secret")
                                    .contentType("application/json")
                                    .content(input))
                    .andExpect(status().isBadRequest());
        verifyNoInteractions(port);
    }

    @Test
    void canonicalEvidenceSurvivesDirectFilterAndErrorsRemainUnwrapped() throws Exception {
        var port = mock(MediaPublicSourcePort.class);
        var now = Instant.now();
        var facts =
                MediaPublicSourceFacts.seal(
                        new Facts(
                                1,
                                "CATALOG_PUBLIC_MEDIA",
                                REQUEST,
                                List.of(
                                        new Asset(
                                                KEY,
                                                "https://img.example/" + KEY,
                                                "image/jpeg",
                                                1234L,
                                                640,
                                                480,
                                                "a".repeat(64))),
                                now,
                                now.plusSeconds(30),
                                null));
        when(port.capture(REQUEST)).thenReturn(facts);
        try (var context = new AnnotationConfigApplicationContext()) {
            TestPropertyValues.of(
                            "shop.media.provider=cos",
                            "shop.media.delivery=direct",
                            "shop.media.direct-base-url=https://cdn.example",
                            "shop.cos.domain=https://img.example")
                    .applyTo(context);
            context.register(MediaDeliveryConfig.class);
            context.refresh();
            var filter = context.getBean(FilterRegistrationBean.class).getFilter();
            var mvc =
                    MockMvcBuilders.standaloneSetup(
                                    new InternalMediaPublicSourceController(port, "secret"))
                            .setControllerAdvice(
                                    new GlobalExceptionHandler(), new ApiResponseWrapper())
                            .addFilters(filter)
                            .build();
            var response =
                    mvc.perform(
                                    post(MediaPublicSourceInternalApi.CAPTURE_PATH)
                                            .header(InternalHttp.TOKEN_HEADER, "secret")
                                            .contentType("application/json")
                                            .content(BODY))
                            .andExpect(status().isOk())
                            .andExpect(
                                    jsonPath("$.assets[0].publicUrl")
                                            .value("https://img.example/" + KEY))
                            .andExpect(jsonPath("$.data").doesNotExist())
                            .andReturn()
                            .getResponse();
            var decoded =
                    JsonMapper.builder()
                            .findAndAddModules()
                            .build()
                            .readValue(response.getContentAsString(), Facts.class);
            MediaPublicSourceFacts.validate(REQUEST, decoded, Instant.now());
            for (var failure :
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
                when(port.capture(REQUEST)).thenThrow(BizException.of(failure.getKey()));
                mvc.perform(
                                post(MediaPublicSourceInternalApi.CAPTURE_PATH)
                                        .header(InternalHttp.TOKEN_HEADER, "secret")
                                        .contentType("application/json")
                                        .content(BODY))
                        .andExpect(status().is(failure.getValue()));
                reset(port);
            }
            when(port.capture(REQUEST))
                    .thenThrow(new IllegalStateException("private-db-or-provider-secret"));
            var error =
                    mvc.perform(
                                    post(MediaPublicSourceInternalApi.CAPTURE_PATH)
                                            .header(InternalHttp.TOKEN_HEADER, "secret")
                                            .contentType("application/json")
                                            .content(BODY))
                            .andExpect(status().isInternalServerError())
                            .andReturn()
                            .getResponse();
            assertThat(error.getContentAsString()).doesNotContain("private-db-or-provider-secret");
        }
    }
}
