package ai.neargo.shop.scenario;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import ai.neargo.shop.common.ApiResponseWrapper;
import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.common.GlobalExceptionHandler;
import ai.neargo.shop.portal.internal.InternalCatalogPriceSourceController;
import ai.neargo.shop.spi.product.CatalogPriceSourceInternalApi;
import ai.neargo.shop.spi.product.CatalogPriceSourcePort;
import ai.neargo.shop.svc.InternalHttp;

import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

class InternalCatalogPriceSourceApiTest {
    static final String BODY = "{\"entityNo\":\"E\",\"storeNo\":\"S\",\"skuNos\":[\"SKU\"]}";

    @Test
    void marketRequiresOnlyOriginalScopeAndCannotAcceptMarketOrCurrencyFromCaller() throws Exception {
        var calls = new AtomicInteger();
        var source = new CatalogPriceSourcePort() {
            @Override public MarketFacts market(MarketScope scope) {
                calls.incrementAndGet();
                return new MarketFacts(1, "CATALOG_MARKET", scope,
                        new Market("AE", "AED", 2), null, null, "fixture");
            }
        };
        var mvc = MockMvcBuilders.standaloneSetup(new InternalCatalogPriceSourceController(source, "wire"))
                .setControllerAdvice(new GlobalExceptionHandler(), new ApiResponseWrapper()).build();
        mvc.perform(post(CatalogPriceSourceInternalApi.MARKET_PATH).contentType("application/json").content("{"))
                .andExpect(status().isUnauthorized());
        for (String bad : List.of("{}", "{\"entityNo\":1,\"storeNo\":\"S\"}",
                "{\"entityNo\":\"E\",\"storeNo\":\"S\",\"market\":\"AE\"}")) {
            mvc.perform(post(CatalogPriceSourceInternalApi.MARKET_PATH).header(InternalHttp.TOKEN_HEADER, "wire")
                    .contentType("application/json").content(bad)).andExpect(status().isBadRequest());
        }
        assertThat(calls).hasValue(0);
        mvc.perform(post(CatalogPriceSourceInternalApi.MARKET_PATH).header(InternalHttp.TOKEN_HEADER, "wire")
                .contentType("application/json").content("{\"entityNo\":\"E\",\"storeNo\":\"S\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.market.currency").value("AED"))
                .andExpect(jsonPath("$.data").doesNotExist());
        assertThat(calls).hasValue(1);
    }

    @Test
    void tokenPrecedesParsingAndCallersCannotSupplyPriceCurrencyTaxOrExtraFields()
            throws Exception {
        var calls = new AtomicInteger();
        var source =
                new CatalogPriceSourcePort() {
                    @Override
                    public Facts capture(Request request) {
                        calls.incrementAndGet();
                        assertThat(request.entityNo()).isEqualTo("E");
                        return new Facts(
                                1,
                                "CATALOG_BASE",
                                request,
                                null,
                                null,
                                List.of(),
                                null,
                                null,
                                "test-only");
                    }
                };
        var mvc =
                MockMvcBuilders.standaloneSetup(
                                new InternalCatalogPriceSourceController(source, "wire"))
                        .setControllerAdvice(new GlobalExceptionHandler(), new ApiResponseWrapper())
                        .build();
        for (String invalid : List.of("{", BODY, "{}", "null", ""))
            mvc.perform(
                            post(CatalogPriceSourceInternalApi.CAPTURE_PATH)
                                    .contentType("application/json")
                                    .content(invalid))
                    .andExpect(status().isUnauthorized());
        for (String invalid :
                List.of(
                        BODY.replace("\"E\"", "1"),
                        BODY.replace("[\"SKU\"]", "[1]"),
                        BODY.replace("[\"SKU\"]", "[\"SKU\",\"SKU\"]"),
                        BODY.substring(0, BODY.length() - 1) + ",\"market\":\"CN\"}",
                        BODY.substring(0, BODY.length() - 1) + ",\"priceMinor\":1}",
                        BODY.substring(0, BODY.length() - 1) + ",\"rateBp\":0}",
                        "{}")) {
            mvc.perform(
                            post(CatalogPriceSourceInternalApi.CAPTURE_PATH)
                                    .header(InternalHttp.TOKEN_HEADER, "wire")
                                    .contentType("application/json")
                                    .content(invalid))
                    .andExpect(status().isBadRequest());
        }
        assertThat(calls).hasValue(0);
        mvc.perform(
                        post(CatalogPriceSourceInternalApi.CAPTURE_PATH)
                                .header(InternalHttp.TOKEN_HEADER, "wire")
                                .contentType("application/json")
                                .content(BODY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.purpose").value("CATALOG_BASE"))
                .andExpect(jsonPath("$.code").doesNotExist());
        assertThat(calls).hasValue(1);
    }

    @Test
    void scopedBusinessFailuresKeepTheirOriginalInternalStatus() throws Exception {
        for (var failure :
                List.of(
                        ErrorCode.NOT_FOUND,
                        ErrorCode.CONFLICT,
                        ErrorCode.PRICING_OWNER_UNSUPPORTED)) {
            var source =
                    new CatalogPriceSourcePort() {
                        @Override
                        public Facts capture(Request request) {
                            throw BizException.of(failure);
                        }
                    };
            var mvc =
                    MockMvcBuilders.standaloneSetup(
                                    new InternalCatalogPriceSourceController(source, "wire"))
                            .setControllerAdvice(
                                    new GlobalExceptionHandler(), new ApiResponseWrapper())
                            .build();
            int expected =
                    failure == ErrorCode.NOT_FOUND
                            ? 404
                            : failure == ErrorCode.CONFLICT ? 409 : 503;
            mvc.perform(
                            post(CatalogPriceSourceInternalApi.CAPTURE_PATH)
                                    .header(InternalHttp.TOKEN_HEADER, "wire")
                                    .contentType("application/json")
                                    .content(BODY))
                    .andExpect(status().is(expected));
        }
    }
}
