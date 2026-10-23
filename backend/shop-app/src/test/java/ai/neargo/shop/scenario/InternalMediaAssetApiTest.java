package ai.neargo.shop.scenario;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import ai.neargo.shop.common.ApiResponseWrapper;
import ai.neargo.shop.common.GlobalExceptionHandler;
import ai.neargo.shop.portal.internal.InternalMediaAssetEndpoint;
import ai.neargo.shop.spi.media.MediaAssetInternalApi;
import ai.neargo.shop.spi.media.MediaAssetQueryPort;
import ai.neargo.shop.spi.media.MediaAssetQueryPort.AssetFact;
import ai.neargo.shop.svc.InternalHttp;
import jakarta.validation.Validation;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class InternalMediaAssetApiTest {
    @Test void tokenPrecedesMalformedBodyAndOwnershipAuthorityFailuresKeepInternalHttpStatus() throws Exception {
        try (var validation = Validation.buildDefaultValidatorFactory()) {
            var count = new AtomicInteger();
            MediaAssetQueryPort port = (entity, store, keys) -> {
                count.incrementAndGet();
                if (store.equals("FAIL")) throw new IllegalStateException("private-db-details");
                return List.of(new AssetFact(keys.getFirst(), entity, store, "GOODS", "ACTIVE"));
            };
            var mvc = MockMvcBuilders.standaloneSetup(new InternalMediaAssetEndpoint(port, "secret",
                    validation.getValidator())).setControllerAdvice(new GlobalExceptionHandler(),
                    new ApiResponseWrapper()).build();
            String body = "{\"entityNo\":\"E\",\"storeNo\":\"S\",\"assetKeys\":[\"E/S/goods/a.jpg\"]}";
            for (String input : List.of(body, "{}", "{", "null", "")) {
                mvc.perform(post(MediaAssetInternalApi.BATCH_PATH).contentType("application/json").content(input))
                        .andExpect(status().isUnauthorized());
            }
            for (String input : List.of("{}", "{", "null", body.replace("a.jpg", "../a.jpg"),
                    body.replace("\"assetKeys\"", "\"unknown\":1,\"assetKeys\""))) {
                mvc.perform(post(MediaAssetInternalApi.BATCH_PATH).header(InternalHttp.TOKEN_HEADER, "secret")
                        .contentType("application/json").content(input)).andExpect(status().isBadRequest());
            }
            assertThat(count).hasValue(0);
            mvc.perform(post(MediaAssetInternalApi.BATCH_PATH).header(InternalHttp.TOKEN_HEADER, "secret")
                    .contentType("application/json").content(body)).andExpect(status().isOk())
                    .andExpect(jsonPath("$[0].assetKey").value("E/S/goods/a.jpg"));
            var response = mvc.perform(post(MediaAssetInternalApi.BATCH_PATH)
                    .header(InternalHttp.TOKEN_HEADER, "secret").contentType("application/json")
                    .content(body.replace("\"S\"", "\"FAIL\"")))
                    .andExpect(status().isInternalServerError()).andReturn().getResponse();
            assertThat(response.getContentAsString()).doesNotContain("private-db-details");
        }
    }
}
