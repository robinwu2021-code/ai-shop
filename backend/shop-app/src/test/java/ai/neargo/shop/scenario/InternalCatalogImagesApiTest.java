package ai.neargo.shop.scenario;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import ai.neargo.shop.common.ApiResponseWrapper;
import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.common.GlobalExceptionHandler;
import ai.neargo.shop.common.pricing.PricingSnapshotHash;
import ai.neargo.shop.portal.internal.InternalGoodsCatalogEndpoint;
import ai.neargo.shop.spi.product.CatalogImageContracts.Expectation;
import ai.neargo.shop.spi.product.CatalogImageContracts.Facts;
import ai.neargo.shop.spi.product.CatalogImageContracts.Item;
import ai.neargo.shop.spi.product.CatalogImageContracts.Query;
import ai.neargo.shop.spi.product.CatalogImageFacts;
import ai.neargo.shop.spi.product.GoodsCatalogInternalApi;
import ai.neargo.shop.spi.product.GoodsCatalogQueryPort;
import ai.neargo.shop.svc.InternalHttp;
import jakarta.validation.Validation;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import tools.jackson.databind.json.JsonMapper;

class InternalCatalogImagesApiTest {
    private final GoodsCatalogQueryPort port = mock(GoodsCatalogQueryPort.class);
    private final JsonMapper json = JsonMapper.builder().build();
    private final Query query = new Query("E", "S", List.of(new Expectation("G", "NONE", null)));

    private MockMvc mvc(String token) {
        try (var validation = Validation.buildDefaultValidatorFactory()) {
            return MockMvcBuilders.standaloneSetup(
                    new InternalGoodsCatalogEndpoint(port, token, validation.getValidator()))
                    .setControllerAdvice(new GlobalExceptionHandler(), new ApiResponseWrapper()).build();
        }
    }

    @Test void serviceTokenPrecedesMalformedOrInvalidBodyAndNeverCallsOwner() throws Exception {
        var mvc = mvc("secret");
        for (String body : List.of("{", "{}", "null", "[]", "{\"entityNo\":7}",
                json.writeValueAsString(query).replace("\"NONE\"", "\"UNKNOWN\""))) {
            mvc.perform(post(GoodsCatalogInternalApi.IMAGES_PATH).header(InternalHttp.TOKEN_HEADER, "wrong")
                    .contentType("application/json").content(body)).andExpect(status().isUnauthorized());
            mvc.perform(post(GoodsCatalogInternalApi.IMAGES_PATH).header(InternalHttp.TOKEN_HEADER, "secret")
                    .contentType("application/json").content(body)).andExpect(status().isBadRequest());
        }
        mvc("").perform(post(GoodsCatalogInternalApi.IMAGES_PATH).header(InternalHttp.TOKEN_HEADER, "secret")
                .contentType("application/json").content(json.writeValueAsString(query)))
                .andExpect(status().isUnauthorized());
        verifyNoInteractions(port);
    }

    @Test void rawResponseKeepsReceiptAndOriginalReferenceBytes() throws Exception {
        var now = Instant.now();
        var f = CatalogImageFacts.seal(new Facts(1, "CATALOG_IMAGE_IDENTITY", query,
                List.of(new Item("G", "NONE", PricingSnapshotHash.of(""), null, 3L, null, null)),
                now, now.plusSeconds(30), null));
        when(port.resolveCatalogImages(query)).thenReturn(f);
        var response = mvc("secret").perform(post(GoodsCatalogInternalApi.IMAGES_PATH)
                .header(InternalHttp.TOKEN_HEADER, "secret").contentType("application/json")
                .content(json.writeValueAsString(query))).andExpect(status().isOk())
                .andExpect(jsonPath("$.purpose").value("CATALOG_IMAGE_IDENTITY"))
                .andExpect(jsonPath("$.code").doesNotExist()).andReturn().getResponse();
        var read = json.readValue(response.getContentAsString(), Facts.class);
        assertThat(read).isEqualTo(f);
        CatalogImageFacts.validate(query, read, Instant.now());
    }

    @Test void originalDenialsAndInfrastructureErrorsKeepRawInternalStatuses() throws Exception {
        var mvc = mvc("secret");
        for (var code : List.of(ErrorCode.NOT_FOUND, ErrorCode.CONFLICT, ErrorCode.FORBIDDEN)) {
            doThrow(BizException.of(code)).when(port).resolveCatalogImages(any());
            var result = mvc.perform(post(GoodsCatalogInternalApi.IMAGES_PATH)
                    .header(InternalHttp.TOKEN_HEADER, "secret").contentType("application/json")
                    .content(json.writeValueAsString(query))).andReturn().getResponse();
            assertThat(result.getStatus()).isEqualTo(switch (code) {
                case NOT_FOUND -> 404;
                case CONFLICT -> 409;
                default -> 403;
            });
            assertThat(result.getContentAsString()).isEmpty();
        }
        doThrow(new IllegalStateException("private internals")).when(port).resolveCatalogImages(any());
        mvc.perform(post(GoodsCatalogInternalApi.IMAGES_PATH).header(InternalHttp.TOKEN_HEADER, "secret")
                .contentType("application/json").content(json.writeValueAsString(query)))
                .andExpect(status().isInternalServerError());
    }
}
