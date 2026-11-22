package ai.neargo.shop.invoice.gateway;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.neargo.shop.invoice.gateway.ProviderProfile.NumberingMode;
import ai.neargo.shop.invoice.gateway.ProviderProfile.ReportMode;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** 供应商注册表：按声明的地区列出候选、按能力取用、编码重复启动失败（PRD AC41）。 */
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

    /** 能力定义完善 D1：能力集合由接口与声明推导；注销要「收 REVOKE」与「能查进度」两样都有。 */
    @Test
    void capabilitiesAreDerivedFromInterfacesAndDeclaredUploads() {
        assertEquals(Set.of(ProviderCapability.QUERY_STATUS), gateways.capabilities("TW_PROVIDER"));
        assertEquals(Set.of(), gateways.capabilities("STUB"));
        assertEquals(Set.of(), gateways.capabilities("NOPE"));
        var base = new ProviderProfile(Set.of("TW_EGUI"), NumberingMode.PROVIDER_RANGE, ReportMode.ASYNC_UPLOAD, true);
        var withRevoke = base.withUploads(Set.of(ProviderOperation.ISSUE, ProviderOperation.REVOKE));
        var g = new InvoiceGateways(List.of(new Revoking("NO_UPLOAD", base), new Revoking("BOTH", withRevoke)));
        assertTrue(!g.has("NO_UPLOAD", ProviderCapability.REVOKE), "能查进度但不收注销报文：不算能注销");
        assertTrue(g.has("BOTH", ProviderCapability.REVOKE));
        assertTrue(base.accepts(ProviderOperation.ISSUE) && !base.accepts(ProviderOperation.REVOKE), "默认收四类标准报文");
        assertTrue(base.allocatable(RangeWindow.NEXT), "默认任意期别");
        assertEquals(QrKeySource.MERCHANT, base.qrKeySource());
        assertEquals(3, base.credentials().size(), "要账号的默认三项通用凭据");
    }

    record Revoking(String code, ProviderProfile profile) implements InvoiceGateway, RevokeStatusCapability {
        public GatewayReceipt send(GatewayRequest request) {
            return GatewayReceipt.accepted(code);
        }

        public RevokeState revokeStatus(ProviderAccount a, String n, LocalDate d) {
            return RevokeState.DONE;
        }
    }

    @Test
    void ac41DuplicateCodesFailAtStartup() {
        var p = new ProviderProfile(Set.of("TW_EGUI"), NumberingMode.LOCAL_RANGE, ReportMode.ASYNC_UPLOAD, false);
        assertThrows(IllegalStateException.class,
                () -> new InvoiceGateways(List.of(new Plain("X", p), new Plain("X", p))));
    }
}
