package ai.neargo.shop.invoice.channel.base;

import static org.junit.jupiter.api.Assertions.assertEquals;

import ai.neargo.shop.invoice.gateway.InvoiceGateway.GatewayReceipt.Outcome;
import ai.neargo.shop.invoice.gateway.InvoiceGateway.GatewayRequest;
import ai.neargo.shop.invoice.gateway.InvoiceGateway.ProviderAccount;
import ai.neargo.shop.invoice.gateway.ProviderProfile;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * 加密接口形态：用一个仿绿界外层的样例厂商，验证模板的「映射 → 加密封装 → 发送 → 外层判定 → 解密 → 码表」。
 * 样例厂商只写了路径、外层与码表 —— 接一家同形态厂商要写的就是这些。
 */
class EncryptedApiInvoiceGatewayTest {

    static final ObjectMapper JSON = JsonMapper.builder().build();
    static final ProviderAccount ACCOUNT = new ProviderAccount("2000132", "ejCk326UnaZWKisg", "q9jcZX8Ib9LM8wYk");

    /** 样例：外层 {MerchantID, Data}，回包 {TransCode, Data}，业务码 RtnCode=1 成功。 */
    static final class SampleEncryptedVendor extends AbstractEncryptedApiInvoiceGateway {
        SampleEncryptedVendor(FakeHttp http) {
            super(http.base(), new HttpTransport(Duration.ofSeconds(5)), JSON);
        }

        public String code() {
            return "SAMPLE_AES";
        }

        public ProviderProfile profile() {
            return new ProviderProfile(Set.of("TW_EGUI"), ProviderProfile.NumberingMode.PROVIDER_ONLINE,
                    ProviderProfile.ReportMode.SYNC_ON_ISSUE, true);
        }

        protected ResultCodeTable resultCodes() {
            return ResultCodeTable.builder().accepted("1").accountProblem(OUTER_REJECTED).build();
        }

        protected String path(String operation) {
            return "/B2CInvoice/Issue";
        }

        protected AesCbcCipher cipher(ProviderAccount a) {
            return new AesCbcCipher(a.keyId(), a.secret(), true);
        }

        protected SealedRequest envelope(ProviderAccount a, String cipherText) {
            var m = new LinkedHashMap<String, Object>();
            m.put("MerchantID", a.accountCode());
            m.put("Data", cipherText);
            return SealedRequest.json(JSON.writeValueAsString(m), Map.of());
        }

        protected String outer(JsonNode response) {
            return response.path("TransCode").asInt() == 1 ? response.path("Data").asText() : null;
        }

        protected ApiOutcome business(String operation, JsonNode d) {
            return new ApiOutcome(d.path("RtnCode").asText(), d.path("InvoiceNo").asText(null),
                    Map.of("fiscalNumber", d.path("InvoiceNo").asText("")));
        }

        protected GatewayReceipt upload(GatewayRequest request, JsonNode message) {
            var out = callApi(request.account(), request.messageType(), Map.of("RelateNumber", request.refNo()));
            return receipt(request.messageType(), out.resultCode(), request.refNo());
        }
    }

    final FakeHttp http;
    final SampleEncryptedVendor vendor;
    final AesCbcCipher cipher = new AesCbcCipher(ACCOUNT.keyId(), ACCOUNT.secret(), true);

    EncryptedApiInvoiceGatewayTest() throws Exception {
        http = new FakeHttp();
        vendor = new SampleEncryptedVendor(http);
    }

    @AfterEach
    void stop() {
        http.close();
    }

    String reply(int transCode, String businessJson) {
        return JSON.writeValueAsString(Map.of("TransCode", transCode, "Data", cipher.encrypt(businessJson)));
    }

    @Test
    void ac41PayloadIsSealedWithTheAccountKeysAndTheReplyIsOpenedAndClassified() {
        http.replies.add(reply(1, "{\"RtnCode\":1,\"InvoiceNo\":\"AB12345678\"}"));
        var receipt = vendor.send(new GatewayRequest("TW_EGUI", "ISSUE", "INV-1", "{}", ACCOUNT));
        assertEquals(Outcome.ACCEPTED, receipt.outcome());
        assertEquals("SAMPLE_AES:1:INV-1", receipt.gatewayRef());

        var seen = http.requests.getFirst();
        assertEquals("/api/B2CInvoice/Issue", seen.path());
        JsonNode sent = JSON.readTree(seen.body());
        assertEquals("2000132", sent.path("MerchantID").asText());
        assertEquals("INV-1", JSON.readTree(cipher.decrypt(sent.path("Data").asText())).path("RelateNumber").asText(),
                "业务报文整体加密，只有持同一组 Key / IV 才解得开");
    }

    @Test
    void ac41OuterRejectionIsAnAccountProblemAndBusinessErrorsAreFinal() {
        http.replies.add(JSON.writeValueAsString(Map.of("TransCode", 999, "Data", "")));
        assertEquals(Outcome.ACCOUNT_PROBLEM,
                vendor.send(new GatewayRequest("TW_EGUI", "ISSUE", "INV-1", "{}", ACCOUNT)).outcome());
        http.replies.add(reply(1, "{\"RtnCode\":5000001}"));
        assertEquals(Outcome.FINAL, vendor.send(new GatewayRequest("TW_EGUI", "ISSUE", "INV-1", "{}", ACCOUNT)).outcome());
    }
}
