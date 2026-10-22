package ai.neargo.shop.scenario;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import ai.neargo.shop.common.ApiResponseWrapper;
import ai.neargo.shop.common.GlobalExceptionHandler;
import ai.neargo.shop.portal.internal.InternalDishOfferEndpoint;
import ai.neargo.shop.spi.product.DishOfferInternalApi;
import ai.neargo.shop.spi.product.DishOfferQueryPort;
import ai.neargo.shop.spi.product.DishOfferQueryPort.Offer;
import ai.neargo.shop.svc.InternalHttp;
import jakarta.validation.Validation;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class InternalDishOfferApiTest {
    @Test void tokenPrecedesMalformedBodyAndReturnsRawOriginalAuthorityFacts() throws Exception {
        try (var validation = Validation.buildDefaultValidatorFactory()) {
            var count = new AtomicInteger();
            DishOfferQueryPort port = (entity, store, keys) -> {
                count.incrementAndGet();
                return List.of(new Offer(keys.getFirst(), "G", entity, store, 500L, "CNY", "KNOWN",
                        null, "UNKNOWN", true));
            };
            var mvc = MockMvcBuilders.standaloneSetup(new InternalDishOfferEndpoint(port, "secret",
                    validation.getValidator())).setControllerAdvice(new GlobalExceptionHandler(),
                    new ApiResponseWrapper()).build();
            String body = "{\"entityNo\":\"E\",\"storeNo\":\"S\",\"skuNos\":[\"SKU\"]}";
            for (String input : List.of(body, "{}", "{", "null", "")) {
                mvc.perform(post(DishOfferInternalApi.BATCH_PATH).contentType("application/json").content(input))
                        .andExpect(status().isUnauthorized());
            }
            mvc.perform(post(DishOfferInternalApi.BATCH_PATH).header(InternalHttp.TOKEN_HEADER, "secret")
                    .contentType("application/json").content(body.replace("[\"SKU\"]", "[\"SKU\",\"SKU\"]")))
                    .andExpect(status().isBadRequest());
            assertThat(count).hasValue(0);
            mvc.perform(post(DishOfferInternalApi.BATCH_PATH).header(InternalHttp.TOKEN_HEADER, "secret")
                    .contentType("application/json").content(body)).andExpect(status().isOk())
                    .andExpect(jsonPath("$[0].priceMinor").value(500))
                    .andExpect(jsonPath("$[0].stockStatus").value("UNKNOWN"));
        }
    }
}
