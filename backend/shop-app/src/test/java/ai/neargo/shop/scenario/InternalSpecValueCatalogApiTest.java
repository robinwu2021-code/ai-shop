package ai.neargo.shop.scenario;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import ai.neargo.shop.common.ApiResponseWrapper;
import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.common.GlobalExceptionHandler;
import ai.neargo.shop.portal.internal.InternalSpecValueCatalogEndpoint;
import ai.neargo.shop.spi.product.SpecValueCatalogInternalApi;
import ai.neargo.shop.spi.product.SpecValueCatalogQueryPort;
import ai.neargo.shop.spi.product.SpecValueCatalogQueryPort.SpecValueFact;
import ai.neargo.shop.svc.InternalHttp;
import ai.neargo.shop.svc.ServiceName;
import ai.neargo.svc.client.ServiceCallException;
import com.sun.net.httpserver.HttpServer;
import jakarta.validation.Validation;
import jakarta.validation.ValidatorFactory;
import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/** Includes public global advice so the internal protocol cannot accidentally acquire a business envelope. */
class InternalSpecValueCatalogApiTest {
    private static final ValidatorFactory VALIDATION = Validation.buildDefaultValidatorFactory();
    private static final String BODY = "{\"entityNo\":\"E\",\"valueNos\":[\"V\"]}";
    private static final SpecValueFact FACT = new SpecValueFact("V", "D", "半糖", "糖度", "ENUM", "PROP",
            "MERCHANT", "E", "PLATFORM", "PLATFORM", 2, 1);
    @AfterAll static void closeValidation() { VALIDATION.close(); }

    private MockMvc mvc(SpecValueCatalogQueryPort port, String token) {
        return MockMvcBuilders.standaloneSetup(new InternalSpecValueCatalogEndpoint(port, token,
                VALIDATION.getValidator())).setControllerAdvice(new GlobalExceptionHandler(),
                        new ApiResponseWrapper()).build();
    }

    @Test void authenticatesBeforeParsingValidationAndNeverInvokesAuthorityForRejectedInputs() throws Exception {
        var count = new AtomicInteger();
        SpecValueCatalogQueryPort port = (entity, numbers) -> { count.incrementAndGet(); return List.of(FACT); };
        var mvc = mvc(port, "secret");
        for (String body : List.of(BODY, "{}", "{", "null", "", "[]")) {
            mvc.perform(post(SpecValueCatalogInternalApi.BATCH_PATH).contentType("application/json").content(body))
                    .andExpect(status().isUnauthorized());
            mvc.perform(post(SpecValueCatalogInternalApi.BATCH_PATH).header(InternalHttp.TOKEN_HEADER, "wrong")
                    .contentType("application/json").content(body)).andExpect(status().isUnauthorized());
        }
        mvc(port, "").perform(post(SpecValueCatalogInternalApi.BATCH_PATH)
                .header(InternalHttp.TOKEN_HEADER, "secret").contentType("application/json").content(BODY))
                .andExpect(status().isUnauthorized());
        for (String body : List.of("{}", "{", "null", "", "[]", BODY.replace("[\"V\"]", "[]"),
                BODY.replace("[\"V\"]", "[\"V\",\"V\"]"), BODY.replace("[\"V\"]", "null"),
                BODY.replace("\"V\"", "\" V\""), BODY.replace("\"entityNo\"", "\"storeNo\":\"X\",\"entityNo\""))) {
            mvc.perform(post(SpecValueCatalogInternalApi.BATCH_PATH).header(InternalHttp.TOKEN_HEADER, "secret")
                    .contentType("application/json").content(body)).andExpect(status().isBadRequest());
        }
        assertThat(count).hasValue(0);
    }

    @Test void returnsRawFactsAndPreservesInfrastructureFailureWithoutPrivateDetails() throws Exception {
        mvc((entity, numbers) -> List.of(FACT), "secret").perform(post(SpecValueCatalogInternalApi.BATCH_PATH)
                .header(InternalHttp.TOKEN_HEADER, "secret").contentType("application/json").content(BODY))
                .andExpect(status().isOk()).andExpect(jsonPath("$[0].valueNo").value("V"))
                .andExpect(jsonPath("$[0].valueVersion").value(2));
        var result = mvc((entity, numbers) -> { throw new IllegalStateException("private-db-detail"); }, "secret")
                .perform(post(SpecValueCatalogInternalApi.BATCH_PATH).header(InternalHttp.TOKEN_HEADER, "secret")
                        .contentType("application/json").content(BODY)).andExpect(status().isInternalServerError())
                .andReturn().getResponse();
        assertThat(result.getContentAsString()).isEmpty();
    }

    @Test void originalTypedHttpRoundTripKeepsArrayOmissionsAndBusinessStatus() throws Exception {
        var mvc = mvc((entity, numbers) -> {
            if (numbers.contains("FAIL")) throw BizException.of(ErrorCode.FORBIDDEN);
            return numbers.contains("V") ? List.of(FACT) : List.of();
        }, "secret");
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext(SpecValueCatalogInternalApi.BATCH_PATH, ex -> {
            try {
                var response = mvc.perform(post(ex.getRequestURI().getPath())
                        .header(InternalHttp.TOKEN_HEADER, ex.getRequestHeaders().getFirst(InternalHttp.TOKEN_HEADER))
                        .contentType("application/json").content(ex.getRequestBody().readAllBytes()))
                        .andReturn().getResponse();
                byte[] bytes = response.getContentAsByteArray();
                ex.getResponseHeaders().set("Content-Type", "application/json");
                ex.sendResponseHeaders(response.getStatus(), bytes.length == 0 ? -1 : bytes.length);
                if (bytes.length > 0) ex.getResponseBody().write(bytes);
            } catch (Exception failure) { throw new IllegalStateException(failure); }
            finally { ex.close(); }
        });
        server.start();
        try {
            var http = new InternalHttp(name -> Optional.of("http://127.0.0.1:" + server.getAddress().getPort()),
                    "secret");
            var api = http.client(ServiceName.PLATFORM, SpecValueCatalogInternalApi.class, Duration.ofSeconds(3));
            assertThat(api.lookupBatch(new SpecValueCatalogInternalApi.BatchLookup("E", List.of("V"))))
                    .containsExactly(FACT);
            assertThat(api.lookupBatch(new SpecValueCatalogInternalApi.BatchLookup("E", List.of("MISSING"))))
                    .isEmpty();
            assertThatThrownBy(() -> api.lookupBatch(new SpecValueCatalogInternalApi.BatchLookup("E", List.of("FAIL"))))
                    .isInstanceOfSatisfying(ServiceCallException.class, e -> assertThat(e.statusCode()).isEqualTo(403));
        } finally { server.stop(0); }
    }
}
