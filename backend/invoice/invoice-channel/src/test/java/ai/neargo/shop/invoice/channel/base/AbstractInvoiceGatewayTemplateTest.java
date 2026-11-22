package ai.neargo.shop.invoice.channel.base;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.neargo.shop.invoice.gateway.InvoiceGateway.GatewayReceipt;
import ai.neargo.shop.invoice.gateway.InvoiceGateway.GatewayRequest;
import ai.neargo.shop.invoice.gateway.InvoiceGateway.ProviderAccount;
import ai.neargo.shop.invoice.gateway.ProviderException;
import ai.neargo.shop.invoice.gateway.ProviderOperation;
import ai.neargo.shop.invoice.gateway.ProviderProfile;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * 能力动作模板 {@code call}（TDD-发票-加值中心模板-鲸跃与汎宇 §1）：先核账号、再送出解读、再按码表判定、成功才读数据；
 * 失败统一抛 {@link ProviderException}；留痕打码覆盖原值与 XML 转义后的写法。
 */
class AbstractInvoiceGatewayTemplateTest {

    static final ProviderAccount ACCOUNT = new ProviderAccount("A-1", "pos<no>", "p&ss");

    static final class Probe extends AbstractInvoiceGateway {
        final List<String> steps = new ArrayList<>();

        Probe() {
            super(JsonMapper.builder().build());
        }

        public String code() {
            return "PROBE";
        }

        public ProviderProfile profile() {
            return new ProviderProfile(Set.of("TW_EGUI"), ProviderProfile.NumberingMode.PROVIDER_RANGE,
                    ProviderProfile.ReportMode.ASYNC_UPLOAD, true);
        }

        @Override
        protected ResultCodeTable resultCodes() {
            return ResultCodeTable.builder().acceptedFor("VERIFY", "OK").accountProblem("BAD").retryable("BUSY").build();
        }

        protected GatewayReceipt upload(GatewayRequest request, JsonNode message) {
            return GatewayReceipt.accepted("x");
        }

        String verify(ProviderAccount account, String code) {
            return call(ProviderOperation.VERIFY, account, () -> {
                steps.add("exchange");
                return new ProviderReply(code, "REF", Map.of("taxId", "12345675"), "<raw/>");
            }, r -> {
                steps.add("read");
                return r.data().get("taxId");
            });
        }
    }

    final Probe probe = new Probe();

    @Test
    void runs_exchange_then_classify_then_read() {
        assertEquals("12345675", probe.verify(ACCOUNT, "OK"));
        assertEquals(List.of("exchange", "read"), probe.steps);
    }

    @Test
    void non_accepted_codes_throw_without_reading() {
        for (String code : List.of("BAD", "BUSY", "WHAT")) {
            probe.steps.clear();
            var e = assertThrows(ProviderException.class, () -> probe.verify(ACCOUNT, code));
            assertEquals(code.toLowerCase(), e.code(), "失败码 = 厂商结果码小写");
            assertEquals(List.of("exchange"), probe.steps, "失败不读数据");
        }
        var e = assertThrows(ProviderException.class, () -> probe.verify(ACCOUNT, "OK2"));
        assertEquals("ok2", e.code(), "码表只认登记过的成功码");
    }

    @Test
    void missing_account_fails_before_exchange() {
        var e = assertThrows(ProviderException.class, () -> probe.verify(null, "OK"));
        assertEquals("account_missing", e.code());
        assertTrue(probe.steps.isEmpty());
    }

    @Test
    void transport_failures_pass_through_unchanged() {
        var boom = new ProviderException("unreachable", null);
        var e = assertThrows(ProviderException.class, () -> probe.call(ProviderOperation.VERIFY, ACCOUNT,
                () -> { throw boom; }, r -> "never"));
        assertSame(boom, e);
    }

    @Test
    void mask_hides_raw_and_xml_escaped_secrets() {
        String wire = "<posno>pos&lt;no&gt;</posno><posid>p&amp;ss</posid> json:{\"k\":\"p&ss\",\"id\":\"pos<no>\"} A-1";
        String masked = probe.mask(wire, ACCOUNT);
        assertFalse(masked.contains("p&amp;ss"));
        assertFalse(masked.contains("pos&lt;no&gt;"));
        assertFalse(masked.contains("p&ss"));
        assertFalse(masked.contains("pos<no>"));
        assertTrue(masked.contains("A-1"), "账号代号不是密钥，保留便于排查");
    }

    @Test
    void reply_to_string_never_prints_raw() {
        var r = new AbstractInvoiceGateway.ProviderReply("OK", null, Map.of("k", "v"), "secret-ish raw");
        assertFalse(r.toString().contains("secret-ish"));
    }
}
