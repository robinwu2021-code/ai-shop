package ai.neargo.shop.invoice.channel.base;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import ai.neargo.shop.invoice.gateway.InvoiceGateway.GatewayReceipt.Outcome;
import ai.neargo.shop.invoice.gateway.InvoiceGateway.GatewayRequest;
import ai.neargo.shop.invoice.gateway.InvoiceGateway.ProviderAccount;
import ai.neargo.shop.invoice.gateway.ProviderProfile;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * 网络接入点形态（阿联酋 Peppol ASP）：用一个样例接入点验证外层投递报文、HMAC 签名头、受理码表。
 * 真实接入点的路径、文档格式（PINT AE）与码表待选定服务商后按其文档填。
 */
class NetworkInvoiceGatewayTest {

    static final ObjectMapper JSON = JsonMapper.builder().build();
    static final ProviderAccount ACCOUNT = new ProviderAccount("0235:100000000000003", "api-key-1", "s3cret");
    static final Clock CLOCK = Clock.fixed(Instant.ofEpochSecond(1_790_000_000L), ZoneOffset.UTC);

    static final class SampleAccessPoint extends AbstractNetworkInvoiceGateway {
        SampleAccessPoint(FakeHttp http) {
            super(http.base(), new HttpTransport(Duration.ofSeconds(5)), JSON, CLOCK);
        }

        public String code() {
            return "SAMPLE_ASP";
        }

        public ProviderProfile profile() {
            return networkProfile(Set.of("AE_EINVOICE"), ProviderProfile.NumberingMode.LOCAL_SEQUENCE);
        }

        protected ResultCodeTable resultCodes() {
            return ResultCodeTable.builder().accepted("ACCEPTED").accountProblem("UNAUTHORIZED")
                    .retryable("BUSY").build();
        }

        protected String path(String operation) {
            return "/documents";
        }

        protected String documentType(String messageType) {
            return switch (messageType) {
                case "ISSUE" -> "INVOICE";
                case "ALLOWANCE" -> "CREDIT_NOTE";
                default -> throw new UnsupportedMessageException("unsupported " + messageType);
            };
        }

        protected Object toNetworkDocument(String messageType, JsonNode message) {
            return Map.of("id", message.path("number").asText(), "total", message.path("total").asLong());
        }

        protected SealedRequest signed(ProviderAccount a, String operation, String body, long ts) {
            return SealedRequest.json(body, Map.of("X-Api-Key", a.keyId(), "X-Timestamp", String.valueOf(ts),
                    "X-Signature", hmacSha256Hex(a.secret(), ts + "\n" + body)));
        }

        protected ApiOutcome accepted(String operation, JsonNode body) {
            return new ApiOutcome(body.path("status").asText(), body.path("documentId").asText(null), Map.of());
        }
    }

    final FakeHttp http;
    final SampleAccessPoint asp;

    NetworkInvoiceGatewayTest() throws Exception {
        http = new FakeHttp();
        asp = new SampleAccessPoint(http);
    }

    @AfterEach
    void stop() {
        http.close();
    }

    GatewayRequest issue(String regime) {
        return new GatewayRequest(regime, "ISSUE", "INV-9", "{\"number\":\"AE-0001\",\"total\":10500}", ACCOUNT);
    }

    @Test
    void ac41DeliversASignedEnvelopeAndUsesTheAccessPointReference() {
        http.replies.add("{\"status\":\"accepted\",\"documentId\":\"DOC-77\"}");
        var receipt = asp.send(issue("AE_EINVOICE"));
        assertEquals(Outcome.ACCEPTED, receipt.outcome());
        assertEquals("SAMPLE_ASP:ACCEPTED:DOC-77", receipt.gatewayRef(), "受理号取接入点给的文档号，查单时用它");

        var seen = http.requests.getFirst();
        JsonNode sent = JSON.readTree(seen.body());
        assertEquals("0235:100000000000003", sent.path("senderId").asText(), "发送方 = 账号代号（参与方标识）");
        assertEquals("INVOICE", sent.path("documentType").asText());
        assertEquals("AE-0001", sent.path("document").path("id").asText());
        assertEquals("api-key-1", seen.header("X-Api-Key"));
        assertEquals(AbstractSignedApiInvoiceGateway.hmacSha256Hex("s3cret", "1790000000\n" + seen.body()),
                seen.header("X-Signature"), "签的是实际发出去的那串报文");
    }

    @Test
    void ac41ClassifiesAccessPointCodes() {
        http.replies.add("{\"status\":\"UNAUTHORIZED\"}");
        assertEquals(Outcome.ACCOUNT_PROBLEM, asp.send(issue("AE_EINVOICE")).outcome());
        http.replies.add("{\"status\":\"BUSY\"}");
        assertEquals(Outcome.RETRYABLE, asp.send(issue("AE_EINVOICE")).outcome());
        assertFalse(asp.send(new GatewayRequest("AE_EINVOICE", "VOID", "INV-9", "{}", ACCOUNT)).accepted(),
                "网络里没有「作废」：不支持的报文种类不送");
    }
}
