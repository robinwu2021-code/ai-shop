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

        /** 非空 = 映射阶段拒绝的原因 */
        String rejectInPrepare;
        int delivered;

        @Override
        protected Prepared prepare(GatewayRequest request, JsonNode message) {
            steps.add("prepare:" + request.account().accountCode());
            if (rejectInPrepare != null) throw new UnsupportedMessageException(rejectInPrepare);
            return new Prepared(message, "payload");
        }

        @Override
        protected GatewayReceipt deliver(GatewayRequest request, Prepared prepared) {
            delivered++;
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

    /** 能力定义完善 D2：预检只跑 prepare、不送出；没账号用占位账号；不在声明里的报文直接不通过。 */
    @Test
    void preflight_runs_prepare_only() {
        var issue = new GatewayRequest("TW_EGUI", "ISSUE", "INV-1", "{}", null);
        assertTrue(probe.preflight(issue).isEmpty());
        assertEquals(List.of("prepare:PREFLIGHT"), probe.steps);
        assertEquals(0, probe.delivered, "预检不出网");

        probe.rejectInPrepare = "zero-rated";
        assertEquals("zero-rated", probe.preflight(issue).orElseThrow());
        var revoke = new GatewayRequest("TW_EGUI", "REVOKE", "INV-1", "{}", ACCOUNT);
        assertTrue(probe.preflight(revoke).orElseThrow().contains("REVOKE"), "默认不收注销报文");
    }

    /** send = prepare + deliver；不在声明里的报文判终态、不送出；映射拒绝判终态。 */
    @Test
    void send_rejects_undeclared_messages_and_mapping_rejections() {
        assertTrue(probe.send(new GatewayRequest("TW_EGUI", "ISSUE", "INV-1", "{}", ACCOUNT)).accepted());
        assertEquals(1, probe.delivered);
        assertEquals(GatewayReceipt.Outcome.FINAL,
                probe.send(new GatewayRequest("TW_EGUI", "REVOKE", "INV-1", "{}", ACCOUNT)).outcome());
        probe.rejectInPrepare = "zero-rated";
        assertEquals(GatewayReceipt.Outcome.FINAL,
                probe.send(new GatewayRequest("TW_EGUI", "ISSUE", "INV-1", "{}", ACCOUNT)).outcome());
        assertEquals(1, probe.delivered, "被拒的都没送出");
    }

    /** D4：能力动作失败按码表分类。 */
    @Test
    void call_failures_carry_a_kind() {
        assertEquals(ProviderException.Kind.ACCOUNT,
                assertThrows(ProviderException.class, () -> probe.verify(ACCOUNT, "BAD")).kind());
        assertEquals(ProviderException.Kind.UNAVAILABLE,
                assertThrows(ProviderException.class, () -> probe.verify(ACCOUNT, "BUSY")).kind());
        assertEquals(ProviderException.Kind.REJECTED,
                assertThrows(ProviderException.class, () -> probe.verify(ACCOUNT, "WHAT")).kind());
        assertEquals(ProviderException.Kind.ACCOUNT,
                assertThrows(ProviderException.class, () -> probe.verify(null, "OK")).kind());
    }

    @Test
    void reply_to_string_never_prints_raw() {
        var r = new AbstractInvoiceGateway.ProviderReply("OK", null, Map.of("k", "v"), "secret-ish raw");
        assertFalse(r.toString().contains("secret-ish"));
    }
}
