package ai.neargo.shop.invoice.gateway;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.invoice.gateway.ProviderProfile.NumberingMode;
import ai.neargo.shop.invoice.gateway.ProviderProfile.ReportMode;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** 供应商注册表：按地区列出、按能力取用、主体选供应商时核对地区与编号方式（PRD AC41）。 */
class InvoiceGatewaysTest {

    record Plain(String code, ProviderProfile profile) implements InvoiceGateway {
        public GatewayReceipt send(GatewayRequest request) {
            return GatewayReceipt.accepted(code);
        }
    }

    record Querying(String code, ProviderProfile profile) implements InvoiceGateway, QueryCapability {
        public GatewayReceipt send(GatewayRequest request) {
            return GatewayReceipt.accepted(code);
        }

        public ProviderInvoiceStatus queryStatus(ProviderAccount a, String n, LocalDate d) {
            return new ProviderInvoiceStatus(ProviderInvoiceStatus.State.ISSUED, "0");
        }
    }

    final InvoiceGateways gateways = new InvoiceGateways(List.of(
            new Querying("TW_PROVIDER", new ProviderProfile(Set.of("TW_EGUI"), NumberingMode.PROVIDER_RANGE,
                    ReportMode.ASYNC_UPLOAD, true)),
            new Plain("TW_ONLINE", new ProviderProfile(Set.of("TW_EGUI"), NumberingMode.PROVIDER_ONLINE,
                    ReportMode.SYNC_ON_ISSUE, true)),
            new Querying("AE_ASP", new ProviderProfile(Set.of("AE_EINVOICE"), NumberingMode.LOCAL_SEQUENCE,
                    ReportMode.NETWORK_DELIVERY, true)),
            new Plain("STUB", new ProviderProfile(Set.of(ProviderProfile.ANY_REGIME), NumberingMode.LOCAL_RANGE,
                    ReportMode.ASYNC_UPLOAD, false))));

    @Test
    void ac41ListsProvidersPerRegion() {
        assertEquals(List.of("STUB", "TW_ONLINE", "TW_PROVIDER"),
                gateways.forRegime("TW_EGUI").stream().map(InvoiceGateway::code).toList());
        assertEquals(List.of("AE_ASP", "STUB"),
                gateways.forRegime("AE_EINVOICE").stream().map(InvoiceGateway::code).toList());
    }

    @Test
    void ac41CapabilitiesAreLookedUpNotCast() {
        assertTrue(gateways.capability("AE_ASP", QueryCapability.class).isPresent());
        assertTrue(gateways.capability("AE_ASP", RangeCapability.class).isEmpty(), "阿联酋没有号段");
        assertTrue(gateways.capability("NOPE", QueryCapability.class).isEmpty());
        assertTrue(gateways.requiresAccount("AE_ASP"));
        assertTrue(!gateways.requiresAccount("STUB"));
    }

    @Test
    void ac41ProfileMayOnlyPickAnInstalledProviderOfItsRegionWithSupportedNumbering() {
        assertEquals("TW_PROVIDER", gateways.requireFor("TW_PROVIDER", "TW_EGUI").code());
        assertEquals("STUB", gateways.requireFor("STUB", "AE_EINVOICE").code());
        for (var bad : List.of(new String[] {"AE_ASP", "TW_EGUI"}, new String[] {"NOPE", "TW_EGUI"},
                new String[] {"TW_ONLINE", "TW_EGUI"}, new String[] {"AE_ASP", "AE_EINVOICE"})) {
            var e = assertThrows(BizException.class, () -> gateways.requireFor(bad[0], bad[1]), bad[0]);
            assertEquals(ErrorCode.INVOICE_GATEWAY_UNSUPPORTED, e.errorCode(), bad[0]);
        }
    }

    @Test
    void ac41DuplicateCodesFailAtStartup() {
        var p = new ProviderProfile(Set.of("TW_EGUI"), NumberingMode.LOCAL_RANGE, ReportMode.ASYNC_UPLOAD, false);
        assertThrows(IllegalStateException.class,
                () -> new InvoiceGateways(List.of(new Plain("X", p), new Plain("X", p))));
    }
}
