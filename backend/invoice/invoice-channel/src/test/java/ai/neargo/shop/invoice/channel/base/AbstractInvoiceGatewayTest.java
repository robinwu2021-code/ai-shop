package ai.neargo.shop.invoice.channel.base;

import static org.junit.jupiter.api.Assertions.assertEquals;

import ai.neargo.shop.invoice.gateway.InvoiceGateway.GatewayReceipt;
import ai.neargo.shop.invoice.gateway.InvoiceGateway.GatewayReceipt.Outcome;
import ai.neargo.shop.invoice.gateway.InvoiceGateway.GatewayRequest;
import ai.neargo.shop.invoice.gateway.InvoiceGateway.ProviderAccount;
import ai.neargo.shop.invoice.gateway.ProviderException;
import ai.neargo.shop.invoice.gateway.ProviderProfile;
import java.util.Set;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * 模板基类的 {@code send}：每家厂商都一样的那几条底线 —— 不核对市场（由系统统一决定用哪家）、要账号没账号算账号问题、
 * 不支持的报文不送半对的票、密钥配错算账号问题、不可达按可重试（TDD-发票模块 §19）。
 */
class AbstractInvoiceGatewayTest {

    static final ProviderAccount ACCOUNT = new ProviderAccount("A-1", "key", "secret");

    static final class Probe extends AbstractInvoiceGateway {
        Supplier<GatewayReceipt> behavior = () -> GatewayReceipt.accepted("OK");
        JsonNode lastMessage;

        Probe() {
            super(JsonMapper.builder().build());
        }

        public String code() {
            return "PROBE";
        }

        public ProviderProfile profile() {
            return new ProviderProfile(Set.of("AE_EINVOICE"), ProviderProfile.NumberingMode.LOCAL_SEQUENCE,
                    ProviderProfile.ReportMode.NETWORK_DELIVERY, true);
        }

        protected GatewayReceipt upload(GatewayRequest request, JsonNode message) {
            lastMessage = message;
            return behavior.get();
        }
    }

    final Probe probe = new Probe();

    GatewayReceipt send(String regime, ProviderAccount account) {
        return probe.send(new GatewayRequest(regime, "ISSUE", "INV-1", "{\"n\":1}", account));
    }

    @Test
    void ac41AccountIsCheckedBeforeTheVendorIsCalledButMarketIsNot() {
        assertEquals(Outcome.ACCOUNT_PROBLEM, send("AE_EINVOICE", null).outcome());
        assertEquals(null, probe.lastMessage, "没账号不调厂商");
        assertEquals(Outcome.ACCEPTED, send("TW_EGUI", ACCOUNT).outcome(), "市场由系统统一决定，供应商层不拦");
        assertEquals(Outcome.ACCEPTED, send("AE_EINVOICE", ACCOUNT).outcome());
        assertEquals(1, probe.lastMessage.path("n").asInt(), "报文解析好再交给厂商");
    }

    @Test
    void ac41VendorFailuresAreClassifiedTheSameForEveryVendor() {
        probe.behavior = () -> {
            throw new ProviderException("unreachable", null);
        };
        var r = send("AE_EINVOICE", ACCOUNT);
        assertEquals(Outcome.RETRYABLE, r.outcome());
        assertEquals("probe unreachable", r.error());

        probe.behavior = () -> {
            throw new AbstractInvoiceGateway.UnsupportedMessageException("zero-rated not supported");
        };
        assertEquals(Outcome.FINAL, send("AE_EINVOICE", ACCOUNT).outcome());

        probe.behavior = () -> {
            throw new AbstractInvoiceGateway.AccountMisconfiguredException("aes-cbc InvalidKeyException", null);
        };
        assertEquals(Outcome.ACCOUNT_PROBLEM, send("AE_EINVOICE", ACCOUNT).outcome());
    }
}
