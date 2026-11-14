package ai.neargo.shop.scenario;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import ai.neargo.shop.common.ApiResponseWrapper;
import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.common.GlobalExceptionHandler;
import ai.neargo.shop.portal.internal.InternalGoodsCatalogEndpoint;
import ai.neargo.shop.spi.product.CatalogMarketContracts.GoodsFacts;
import ai.neargo.shop.spi.product.CatalogMarketContracts.GoodsQuery;
import ai.neargo.shop.spi.product.CatalogMarketContracts.SkuFacts;
import ai.neargo.shop.spi.product.CatalogMarketContracts.SkuQuery;
import ai.neargo.shop.spi.product.CatalogMarketFacts;
import ai.neargo.shop.spi.product.CatalogPriceSourceFacts;
import ai.neargo.shop.spi.product.CatalogPriceSourcePort.Market;
import ai.neargo.shop.spi.product.CatalogPriceSourcePort.MarketFacts;
import ai.neargo.shop.spi.product.CatalogPriceSourcePort.MarketScope;
import ai.neargo.shop.spi.product.GoodsCatalogInternalApi;
import ai.neargo.shop.spi.product.GoodsCatalogQueryPort;
import ai.neargo.shop.svc.InternalHttp;
import jakarta.validation.Validation;
import jakarta.validation.ValidatorFactory;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import tools.jackson.databind.json.JsonMapper;

class InternalCatalogMarketApiTest {
    private static final ValidatorFactory VALIDATION = Validation.buildDefaultValidatorFactory();
    private final GoodsCatalogQueryPort port = mock(GoodsCatalogQueryPort.class);
    private final JsonMapper json = JsonMapper.builder().build();
    private final GoodsQuery goods = new GoodsQuery("E", "S", "G", "AE");
    private final SkuQuery sku = new SkuQuery("E", "S", "SKU", "AE");

    @AfterAll static void close() { VALIDATION.close(); }

    private MockMvc mvc(String token) {
        return MockMvcBuilders.standaloneSetup(new InternalGoodsCatalogEndpoint(port, token, VALIDATION.getValidator()))
                .setControllerAdvice(new GlobalExceptionHandler(), new ApiResponseWrapper()).build();
    }

    @Test void bothInternalRoutesAuthenticateBeforeAnyMalformedOrInvalidInput() throws Exception {
        var mvc = mvc("secret");
        for (String path : List.of(GoodsCatalogInternalApi.MARKET_GOODS_PATH,
                GoodsCatalogInternalApi.MARKET_SKU_PATH)) {
            for (String body : List.of("{", "null", "{}", "[]", "{\"entityNo\":7}",
                    "{\"entityNo\":\"E\",\"storeNo\":\"S\",\"market\":\"CN\"}",
                    json.writeValueAsString(goods).replace("\"AE\"", "\"ae\""))) {
                mvc.perform(post(path).header(InternalHttp.TOKEN_HEADER, "wrong")
                        .contentType("application/json").content(body)).andExpect(status().isUnauthorized());
                mvc.perform(post(path).header(InternalHttp.TOKEN_HEADER, "secret")
                        .contentType("application/json").content(body)).andExpect(status().isBadRequest());
            }
            mvc("").perform(post(path).header(InternalHttp.TOKEN_HEADER, "secret")
                    .contentType("application/json").content("{}" )).andExpect(status().isUnauthorized());
        }
        verifyNoInteractions(port);
    }

    @Test void rawMarketAndOriginalMetadataReceiptsRoundTripThroughOriginalController() throws Exception {
        var at = Instant.now();
        var market = CatalogPriceSourceFacts.sealMarket(new MarketFacts(1, "CATALOG_MARKET", new MarketScope("E", "S"),
                new Market("AE", "AED", 2), at, at.plusSeconds(30), null));
        var gf = CatalogMarketFacts.seal(new GoodsFacts(1, "CATALOG_GOODS", goods, market,
                new GoodsCatalogQueryPort.GoodsFact("G", "E", "S", "Dish", null, false, "PENDING", 3,
                        List.of(new GoodsCatalogQueryPort.SkuFact("SKU", null, 8))), at, at.plusSeconds(20), null));
        var sf = CatalogMarketFacts.seal(new SkuFacts(1, "CATALOG_SKU", sku, market,
                new GoodsCatalogQueryPort.SkuIdentityFact("SKU", "G", "E", "S", 8), 3L, at, at.plusSeconds(20), null));
        when(port.getCatalogGoods(goods)).thenReturn(gf);
        when(port.getCatalogSku(sku)).thenReturn(sf);
        var mvc = mvc("secret");
        var gr = mvc.perform(post(GoodsCatalogInternalApi.MARKET_GOODS_PATH).header(InternalHttp.TOKEN_HEADER, "secret")
                .contentType("application/json").content(json.writeValueAsString(goods))).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        var sr = mvc.perform(post(GoodsCatalogInternalApi.MARKET_SKU_PATH).header(InternalHttp.TOKEN_HEADER, "secret")
                .contentType("application/json").content(json.writeValueAsString(sku))).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat(gr).doesNotContain("\"code\"", "\"data\"");
        assertThat(json.readValue(gr, GoodsFacts.class)).isEqualTo(gf);
        assertThat(json.readValue(sr, SkuFacts.class)).isEqualTo(sf);
        CatalogMarketFacts.validate(goods, json.readValue(gr, GoodsFacts.class), Instant.now());
        CatalogMarketFacts.validate(sku, json.readValue(sr, SkuFacts.class), Instant.now());
    }

    @Test void ownerDenialsUnsupportedAndInfrastructureErrorsNeverBecomeEmptySuccess() throws Exception {
        var mvc = mvc("secret");
        for (var code : List.of(ErrorCode.NOT_FOUND, ErrorCode.FORBIDDEN, ErrorCode.CONFLICT,
                ErrorCode.PRICING_OWNER_UNSUPPORTED)) {
            doThrow(BizException.of(code)).when(port).getCatalogGoods(any());
            doThrow(BizException.of(code)).when(port).getCatalogSku(any());
            int expected = switch (code) {
                case NOT_FOUND -> 404;
                case FORBIDDEN -> 403;
                case CONFLICT -> 409;
                default -> 503;
            };
            for (String path : List.of(GoodsCatalogInternalApi.MARKET_GOODS_PATH,
                GoodsCatalogInternalApi.MARKET_SKU_PATH)) {
                var result = mvc.perform(post(path).header(InternalHttp.TOKEN_HEADER, "secret")
                        .contentType("application/json").content(json.writeValueAsString(
                                path.equals(GoodsCatalogInternalApi.MARKET_GOODS_PATH) ? goods : sku)))
                        .andReturn().getResponse();
                assertThat(result.getStatus()).isEqualTo(expected);
                assertThat(result.getContentAsString()).isEmpty();
            }
        }
        doThrow(new IllegalStateException("private failure")).when(port).getCatalogGoods(any());
        mvc.perform(post(GoodsCatalogInternalApi.MARKET_GOODS_PATH).header(InternalHttp.TOKEN_HEADER, "secret")
                .contentType("application/json").content(json.writeValueAsString(goods)))
                .andExpect(status().isInternalServerError());
    }
}
