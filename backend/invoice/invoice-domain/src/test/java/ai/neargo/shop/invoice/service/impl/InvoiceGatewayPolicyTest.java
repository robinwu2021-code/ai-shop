package ai.neargo.shop.invoice.service.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.invoice.entity.IvcRegimeProfile;
import ai.neargo.shop.invoice.gateway.InvoiceGateway;
import ai.neargo.shop.invoice.gateway.InvoiceGateways;
import ai.neargo.shop.invoice.gateway.ProviderProfile;
import ai.neargo.shop.invoice.gateway.ProviderProfile.NumberingMode;
import ai.neargo.shop.invoice.gateway.ProviderProfile.ReportMode;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

/** 供应商由系统统一决定：默认市场、市场默认供应商（换阶段只改配置）、商家在支持的供应商里自选（PRD AC42）。 */
class InvoiceGatewayPolicyTest {

    record G(String code, ProviderProfile profile) implements InvoiceGateway {
        public GatewayReceipt send(GatewayRequest request) {
            return GatewayReceipt.accepted(code);
        }
    }

    static final ProviderProfile TW = new ProviderProfile(Set.of("TW_EGUI"), NumberingMode.PROVIDER_RANGE,
            ReportMode.ASYNC_UPLOAD, true);
    static final InvoiceGateways GATEWAYS = new InvoiceGateways(List.of(new G("CETUSTEK", TW), new G("ECPAY", TW),
            new G("AE_ASP", new ProviderProfile(Set.of("AE_EINVOICE"), NumberingMode.LOCAL_SEQUENCE,
                    ReportMode.NETWORK_DELIVERY, true)),
            new G("STUB", new ProviderProfile(Set.of(ProviderProfile.ANY_REGIME), NumberingMode.LOCAL_RANGE,
                    ReportMode.ASYNC_UPLOAD, false))));

    static IvcRegimeProfile profile(String gatewayCode) {
        var p = new IvcRegimeProfile();
        p.setRegimeCode("TW_EGUI");
        p.setGatewayCode(gatewayCode);
        return p;
    }

    static List<String> codes(List<InvoiceGateway> gs) {
        return gs.stream().map(InvoiceGateway::code).toList();
    }

    @Test
    void ac42UnconfiguredSystemKeepsTodaysBehaviour() {
        var policy = new InvoiceGatewayPolicy(GATEWAYS, new MockEnvironment());
        assertEquals("TW_EGUI", policy.defaultRegime());
        assertEquals("STUB", policy.defaultGateway("TW_EGUI"));
        assertEquals(List.of("STUB", "CETUSTEK", "ECPAY"), codes(policy.selectable("TW_EGUI")),
                "没配支持列表 = 声明服务该市场的都可选，默认排第一");
        assertEquals(List.of("STUB", "AE_ASP"), codes(policy.selectable("AE_EINVOICE")));
    }

    @Test
    void ac42SwitchingThePhaseIsOneConfigLineAndMerchantChoiceWins() {
        var env = new MockEnvironment().withProperty("shop.invoice.regimes.TW_EGUI.default-gateway", "CETUSTEK")
                .withProperty("shop.invoice.regimes.TW_EGUI.gateways", "ECPAY, NOT_INSTALLED");
        var policy = new InvoiceGatewayPolicy(GATEWAYS, env);
        assertEquals(new InvoiceGatewayPolicy.Effective("CETUSTEK", "DEFAULT"), policy.effective(profile(null)));
        assertEquals(new InvoiceGatewayPolicy.Effective("ECPAY", "MERCHANT"), policy.effective(profile("ECPAY")));
        assertEquals(List.of("CETUSTEK", "ECPAY"), codes(policy.selectable("TW_EGUI")),
                "配了支持列表就只开放这些（没装配的跳过），默认总在其中");
        policy.requireSelectable("TW_EGUI", "ECPAY");
        for (String bad : List.of("STUB", "AE_ASP", "NOT_INSTALLED")) {
            var e = assertThrows(BizException.class, () -> policy.requireSelectable("TW_EGUI", bad), bad);
            assertEquals(ErrorCode.INVOICE_GATEWAY_UNSUPPORTED, e.errorCode());
        }
    }
}
