package ai.neargo.shop.scenario;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import ai.neargo.shop.common.ApiResponseWrapper;
import ai.neargo.shop.common.GlobalExceptionHandler;
import ai.neargo.shop.portal.internal.InternalStoreCardEndpoint;
import ai.neargo.shop.spi.user.StoreCardInternalApi;
import ai.neargo.shop.spi.user.StoreCardQueryPort;
import ai.neargo.shop.spi.user.StoreDirectoryPort;
import ai.neargo.shop.svc.InternalHttp;
import jakarta.validation.Validation;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class InternalStoreCardApiTest {
    @Test void serviceTokenPrecedesMalformedInputsAndOriginalOwnerIsResolvedOnlyByAuthority() throws Exception {
        try (var validation = Validation.buildDefaultValidatorFactory()) {
            var calls = new AtomicInteger();
            StoreCardQueryPort port = keys -> {
                calls.incrementAndGet();
                return Map.of("S", new StoreDirectoryPort.StoreCard("S", "Store", "E", null, "READONLY", false,
                        "08:00-22:00", null, null, null, 0, 0));
            };
            var mvc = MockMvcBuilders.standaloneSetup(new InternalStoreCardEndpoint(port, "secret",
                    validation.getValidator()))
                    .setControllerAdvice(new GlobalExceptionHandler(), new ApiResponseWrapper()).build();
            String body = "{\"storeNos\":[\"S\"]}";
            for (String input : List.of(body, "{}", "{", "null", "")) {
                mvc.perform(post(StoreCardInternalApi.BATCH_PATH).contentType("application/json").content(input))
                        .andExpect(status().isUnauthorized());
            }
            for (String invalid : List.of("{\"storeNos\":[\"S\",\"S\"]}", "{\"storeNos\":[1]}",
                    "{\"storeNos\":[\"S\"],\"entityNo\":\"OTHER\"}")) {
                mvc.perform(post(StoreCardInternalApi.BATCH_PATH).header(InternalHttp.TOKEN_HEADER, "secret")
                        .contentType("application/json").content(invalid)).andExpect(status().isBadRequest());
            }
            assertThat(calls).hasValue(0);
            mvc.perform(post(StoreCardInternalApi.BATCH_PATH).header(InternalHttp.TOKEN_HEADER, "secret")
                    .contentType("application/json").content(body)).andExpect(status().isOk())
                    .andExpect(jsonPath("$[0].entityNo").value("E"))
                    .andExpect(jsonPath("$[0].status").value("READONLY"));
        }
    }
}
