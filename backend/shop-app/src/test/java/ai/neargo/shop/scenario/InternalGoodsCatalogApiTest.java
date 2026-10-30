package ai.neargo.shop.scenario;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.common.GlobalExceptionHandler;
import ai.neargo.shop.common.ApiResponseWrapper;
import ai.neargo.shop.portal.internal.InternalGoodsCatalogEndpoint;
import ai.neargo.shop.spi.product.GoodsCatalogInternalApi;
import ai.neargo.shop.spi.product.GoodsCatalogQueryPort;
import ai.neargo.shop.spi.product.GoodsCatalogQueryPort.GoodsSummary;
import static org.mockito.Mockito.when;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.anyList;
import java.util.List;
import ai.neargo.shop.spi.product.GoodsCatalogQueryPort.GoodsFact;
import ai.neargo.shop.spi.product.GoodsCatalogQueryPort.SkuFact;
import ai.neargo.shop.svc.InternalHttp;
import ai.neargo.shop.svc.ServiceName;
import ai.neargo.svc.client.ServiceCallException;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.AfterAll;
import jakarta.validation.Validation;
import jakarta.validation.ValidatorFactory;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class InternalGoodsCatalogApiTest {
    private static final ValidatorFactory VALIDATION=Validation.buildDefaultValidatorFactory();
    @AfterAll static void closeValidation() { VALIDATION.close(); }
    private static final String BODY="{\"entityNo\":\"E\",\"storeNo\":\"S\",\"goodsNo\":\"G\"}";
    private static final GoodsFact FACT=new GoodsFact("G","E","S","菜品",null,false,"PENDING",3,
            List.of(new SkuFact("SKU1","小份",0),new SkuFact("SKU2","大份",1)));

    @Test void skuMeasurementAuthenticatesBeforeCallingTheOwner() throws Exception {
        var port = mock(GoodsCatalogQueryPort.class);
        var mvc = MockMvcBuilders.standaloneSetup(
                new InternalGoodsCatalogEndpoint(port, "secret", VALIDATION.getValidator()))
                .setControllerAdvice(new GlobalExceptionHandler(), new ApiResponseWrapper()).build();
        String body = "{\"entityNo\":\"E\",\"storeNo\":\"S\",\"skuNo\":\"SKU\",\"expectedMarket\":\"AE\"}";
        mvc.perform(post(GoodsCatalogInternalApi.SKU_MEASUREMENT_PATH).header(InternalHttp.TOKEN_HEADER, "wrong")
                .contentType("application/json").content(body)).andExpect(status().isUnauthorized());
        verifyNoInteractions(port);
        var query = new ai.neargo.shop.spi.product.CatalogMarketContracts.SkuQuery("E", "S", "SKU", "AE");
        when(port.getCatalogSkuMeasurement(query)).thenThrow(BizException.of(ErrorCode.NOT_FOUND));
        mvc.perform(post(GoodsCatalogInternalApi.SKU_MEASUREMENT_PATH).header(InternalHttp.TOKEN_HEADER, "secret")
                .contentType("application/json").content(body)).andExpect(status().isNotFound());
    }

    @Test void skuInfrastructureFailuresKeepInternal500AndDoNotBypassToken() throws Exception {
        var port = mock(GoodsCatalogQueryPort.class);
        when(port.getSku("E", "S", "SKU")).thenThrow(new IllegalStateException("private database failure"));
        var mvc = MockMvcBuilders.standaloneSetup(new InternalGoodsCatalogEndpoint(port, "secret", VALIDATION.getValidator()))
                .setControllerAdvice(new GlobalExceptionHandler(), new ApiResponseWrapper()).build();
        String body = "{\"entityNo\":\"E\",\"storeNo\":\"S\",\"skuNo\":\"SKU\"}";
        mvc.perform(post(GoodsCatalogInternalApi.SKU_PATH).header(InternalHttp.TOKEN_HEADER, "wrong")
                .contentType("application/json").content(body)).andExpect(status().isUnauthorized());
        verifyNoInteractions(port);
        var response = mvc.perform(post(GoodsCatalogInternalApi.SKU_PATH).header(InternalHttp.TOKEN_HEADER, "secret")
                .contentType("application/json").content(body)).andExpect(status().isInternalServerError())
                .andReturn().getResponse();
        assertThat(response.getContentAsString()).isEmpty();
    }

    @Test void skuLookupAuthenticatesAndReturnsRawScopedIdentity() throws Exception {
        var port = mock(GoodsCatalogQueryPort.class);
        when(port.getSku("E", "S", "SKU")).thenReturn(
                new GoodsCatalogQueryPort.SkuIdentityFact("SKU", "G", "E", "S", 2));
        var mvc = MockMvcBuilders.standaloneSetup(new InternalGoodsCatalogEndpoint(port, "secret", VALIDATION.getValidator()))
                .setControllerAdvice(new GlobalExceptionHandler(), new ApiResponseWrapper()).build();
        String body = "{\"entityNo\":\"E\",\"storeNo\":\"S\",\"skuNo\":\"SKU\"}";
        mvc.perform(post(GoodsCatalogInternalApi.SKU_PATH).contentType("application/json").content(body))
                .andExpect(status().isUnauthorized());
        verifyNoInteractions(port);
        mvc.perform(post(GoodsCatalogInternalApi.SKU_PATH).header(InternalHttp.TOKEN_HEADER, "secret")
                .contentType("application/json").content(body)).andExpect(status().isOk())
                .andExpect(jsonPath("$.skuNo").value("SKU")).andExpect(jsonPath("$.version").value(2));
        mvc.perform(post(GoodsCatalogInternalApi.SKU_PATH).header(InternalHttp.TOKEN_HEADER, "secret")
                .contentType("application/json").content("{}")).andExpect(status().isBadRequest());
    }

    @Test void missingWrongOrUnconfiguredTokenNeverInvokesPort() throws Exception {
        var port=mock(GoodsCatalogQueryPort.class);
        var mvc=MockMvcBuilders.standaloneSetup(new InternalGoodsCatalogEndpoint(port,"secret",VALIDATION.getValidator())).setControllerAdvice(new GlobalExceptionHandler(),new ApiResponseWrapper()).build();
        mvc.perform(post(GoodsCatalogInternalApi.LOOKUP_PATH).contentType("application/json").content(BODY)).andExpect(status().isUnauthorized());
        mvc.perform(post(GoodsCatalogInternalApi.LOOKUP_PATH).header(InternalHttp.TOKEN_HEADER,"wrong").contentType("application/json").content(BODY)).andExpect(status().isUnauthorized());
        var off=MockMvcBuilders.standaloneSetup(new InternalGoodsCatalogEndpoint(port,"",VALIDATION.getValidator())).setControllerAdvice(new GlobalExceptionHandler(),new ApiResponseWrapper()).build();
        off.perform(post(GoodsCatalogInternalApi.LOOKUP_PATH).header(InternalHttp.TOKEN_HEADER,"secret").contentType("application/json").content(BODY)).andExpect(status().isUnauthorized());
        verifyNoInteractions(port);
    }
    @Test void validatesBodyAndPreservesNotFoundWithoutPrivateDetails() throws Exception {
        GoodsCatalogQueryPort port=mock(GoodsCatalogQueryPort.class);
        when(port.getGoods(anyString(),anyString(),anyString())).thenThrow(BizException.of(ErrorCode.NOT_FOUND));
        var mvc=MockMvcBuilders.standaloneSetup(new InternalGoodsCatalogEndpoint(port,"secret",VALIDATION.getValidator())).setControllerAdvice(new GlobalExceptionHandler(),new ApiResponseWrapper()).build();
        mvc.perform(post(GoodsCatalogInternalApi.LOOKUP_PATH).header(InternalHttp.TOKEN_HEADER,"secret")
                .contentType("application/json").content("{}")).andExpect(status().isBadRequest());
        var result=mvc.perform(post(GoodsCatalogInternalApi.LOOKUP_PATH).header(InternalHttp.TOKEN_HEADER,"secret")
                .contentType("application/json").content(BODY)).andExpect(status().isNotFound()).andReturn();
        assertThat(result.getResponse().getContentAsString()).isEmpty();
    }
    @Test void invalidAndMalformedBodiesKeepInternalStatusAndAuthenticateFirst() throws Exception {
        var port=mock(GoodsCatalogQueryPort.class);
        var mvc=MockMvcBuilders.standaloneSetup(new InternalGoodsCatalogEndpoint(port,"secret",VALIDATION.getValidator()))
                .setControllerAdvice(new GlobalExceptionHandler(),new ApiResponseWrapper()).build();
        for(String body:new String[]{"{}","{","null","","[]"}) {
            mvc.perform(post(GoodsCatalogInternalApi.LOOKUP_PATH).header(InternalHttp.TOKEN_HEADER,"secret")
                    .contentType("application/json").content(body)).andExpect(status().isBadRequest());
            mvc.perform(post(GoodsCatalogInternalApi.LOOKUP_PATH).header(InternalHttp.TOKEN_HEADER,"wrong")
                    .contentType("application/json").content(body)).andExpect(status().isUnauthorized());
            mvc.perform(post(GoodsCatalogInternalApi.LOOKUP_PATH).contentType("application/json").content(body))
                    .andExpect(status().isUnauthorized());
        }
        verifyNoInteractions(port);
    }
    @Test void actualTypedHttpRoundTripUsesSameMetadataAndErrorStatus() throws Exception {
        GoodsCatalogQueryPort port=mock(GoodsCatalogQueryPort.class);
        when(port.getGoods(anyString(),anyString(),anyString())).thenAnswer(inv -> {
            String e=inv.getArgument(0),s=inv.getArgument(1),g=inv.getArgument(2);
            if(!"E".equals(e)||!"S".equals(s)||!"G".equals(g)) throw BizException.of(ErrorCode.NOT_FOUND);
            return FACT;
        });
        when(port.getGoodsBatch(anyString(),anyString(),anyList())).thenReturn(List.of(
                new GoodsSummary("G","E","S","菜品",null,false,"PENDING",3)));
        var skuIdentity = new GoodsCatalogQueryPort.SkuIdentityFact("SKU1", "G", "E", "S", 1);
        when(port.getSku("E", "S", "SKU1")).thenReturn(skuIdentity);
        var mvc=MockMvcBuilders.standaloneSetup(new InternalGoodsCatalogEndpoint(port,"secret",VALIDATION.getValidator())).setControllerAdvice(new GlobalExceptionHandler(),new ApiResponseWrapper()).build();
        var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/internal/product/catalog/goods/",exchange -> {
            try {
                var result=mvc.perform(post(exchange.getRequestURI().getPath())
                        .header(InternalHttp.TOKEN_HEADER,exchange.getRequestHeaders().getFirst(InternalHttp.TOKEN_HEADER))
                        .contentType("application/json").content(exchange.getRequestBody().readAllBytes())).andReturn().getResponse();
                byte[] body=result.getContentAsByteArray();exchange.getResponseHeaders().set("Content-Type","application/json");
                exchange.sendResponseHeaders(result.getStatus(),body.length==0?-1:body.length);
                if(body.length>0) exchange.getResponseBody().write(body);
            } catch(Exception failure) {
                byte[] body="test bridge failure".getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(500,body.length);exchange.getResponseBody().write(body);
            } finally { exchange.close(); }
        });server.start();
        try {
            var http=new InternalHttp(name -> {
                assertThat(name).isEqualTo(ServiceName.PLATFORM);
                return Optional.of("http://127.0.0.1:"+server.getAddress().getPort());
            },"secret");
            var api=http.client(ServiceName.PLATFORM,GoodsCatalogInternalApi.class,Duration.ofSeconds(5));
            assertThat(api.lookupBatch(new GoodsCatalogInternalApi.BatchLookup("E","S",List.of("G"))))
                    .extracting(GoodsSummary::goodsNo).containsExactly("G");
            assertThat(api.lookup(new GoodsCatalogInternalApi.Lookup("E","S","G"))).isEqualTo(FACT);
            assertThat(api.lookupSku(new GoodsCatalogInternalApi.SkuLookup("E", "S", "SKU1"))).isEqualTo(skuIdentity);
            assertThatThrownBy(() -> api.lookup(new GoodsCatalogInternalApi.Lookup("E","","G")))
                    .isInstanceOfSatisfying(ServiceCallException.class,e -> assertThat(e.statusCode()).isEqualTo(400));
            assertThatThrownBy(() -> api.lookup(new GoodsCatalogInternalApi.Lookup("E","OTHER","G")))
                    .isInstanceOfSatisfying(ServiceCallException.class,e -> assertThat(e.statusCode()).isEqualTo(404));
        } finally { server.stop(0); }
    }
    @Test void batchAuthenticatesBeforeValidationAndKeepsRawProtocol() throws Exception {
        var port=mock(GoodsCatalogQueryPort.class);
        when(port.getGoodsBatch(anyString(),anyString(),anyList())).thenReturn(List.of());
        var mvc=MockMvcBuilders.standaloneSetup(new InternalGoodsCatalogEndpoint(port,"secret",VALIDATION.getValidator()))
                .setControllerAdvice(new GlobalExceptionHandler(),new ApiResponseWrapper()).build();
        mvc.perform(post(GoodsCatalogInternalApi.BATCH_PATH).contentType("application/json").content("{}"))
                .andExpect(status().isUnauthorized());
        mvc.perform(post(GoodsCatalogInternalApi.BATCH_PATH).header(InternalHttp.TOKEN_HEADER,"secret")
                .contentType("application/json").content("{\"entityNo\":\"E\",\"storeNo\":\"S\",\"goodsNos\":[]}"))
                .andExpect(status().isBadRequest());
        mvc.perform(post(GoodsCatalogInternalApi.BATCH_PATH).header(InternalHttp.TOKEN_HEADER,"secret")
                .contentType("application/json").content("{\"entityNo\":\"E\",\"storeNo\":\"S\",\"goodsNos\":[\"G\"]}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$").isArray());
    }
}
