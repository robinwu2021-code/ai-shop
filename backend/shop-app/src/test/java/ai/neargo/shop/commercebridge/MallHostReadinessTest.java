package ai.neargo.shop.commercebridge;

import static org.junit.jupiter.api.Assertions.*;

import ai.neargo.commerce.api.catalog.*;
import ai.neargo.commerce.api.common.*;
import ai.neargo.commerce.api.port.CheckoutReadinessPort;
import ai.neargo.commerce.starter.security.CommerceRoutePolicy;

import org.junit.jupiter.api.Test;

import java.util.*;

class MallHostReadinessTest {
    @Test
    void emptyRegistryAndUnavailablePaymentDoNotPreventCatalogConfigurationOrInventAcceptance() {
        var registry = new ShopMallDeploymentRegistry(List.of(), Map.of());
        assertTrue(registry.acceptedDeployments("MAIN").isEmpty());
        var end =
                new BusinessEndDraft(
                        "END_APP_POS",
                        "END_APP_POS",
                        "POS",
                        EndScope.STORE,
                        List.of("RETAIL"),
                        ReleaseStatus.VERIFIED,
                        true,
                        false,
                        true,
                        "client",
                        "policy",
                        "invented",
                        0,
                        "draft");
        assertFalse(registry.verified("MAIN", end));
        assertTrue(registry.requiredEntitlementCodes("MAIN", "RETAIL").isEmpty());
        var stock = new ShopMallHardwareInventoryAdapter(null, Map.of());
        var readiness = new ShopMallCheckoutReadiness(false, stock, List::of);
        var store =
                new StoreSnapshot(
                        new StoreRef("MAIN", "S"),
                        "merchant",
                        "RETAIL",
                        StoreStatus.ACTIVE,
                        CommerceModel.STORE_V1);
        assertFalse(readiness.evaluate(store, new Money("TWD", 69900), List.of()).available());
        assertFalse(readiness.evaluate(store, new Money("TWD", 0), List.of()).available());
        var enabled = new ShopMallCheckoutReadiness(true, stock, List::of);
        assertTrue(enabled.evaluate(store, new Money("TWD", 0), List.of()).available());
        assertFalse(enabled.evaluate(store, new Money("TWD", 69900), List.of()).available());
        var cny =
                new ShopMallCheckoutReadiness(
                        true,
                        stock,
                        () ->
                                List.of(
                                        new CheckoutReadinessPort.PaymentOption(
                                                "WECHAT", "JSAPI", "CNY", Integer.MAX_VALUE)));
        assertTrue(
                cny.evaluate(store, new Money("CNY", 100), List.of())
                        .reasons()
                        .contains("PAYER_BINDING_UNPROVEN"));
        var option =
                new BusinessEndDeploymentOption(
                        "accepted-pos",
                        "POS",
                        CommerceRoutePolicy.POS,
                        CommerceRoutePolicy.POS,
                        List.of("RETAIL"),
                        "pos-client",
                        "commerce-route-v1:POS",
                        "acceptance-2026-11");
        var accepted =
                new ShopMallDeploymentRegistry(
                        List.of(option), Map.of("RETAIL", Set.of(CommerceRoutePolicy.POS)));
        assertTrue(
                accepted.verified(
                        "MAIN",
                        new BusinessEndDraft(
                                option.code(),
                                option.entitlementCode(),
                                "renamed",
                                EndScope.STORE,
                                List.of("RETAIL"),
                                ReleaseStatus.VERIFIED,
                                true,
                                false,
                                true,
                                option.clientId(),
                                option.routePolicyRef(),
                                option.acceptanceRef(),
                                0,
                                "publish")));
        assertFalse(accepted.verified("foreign", end));
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        new ShopMallDeploymentRegistry(
                                List.of(
                                        new BusinessEndDeploymentOption(
                                                "x",
                                                "X",
                                                "X",
                                                "X",
                                                List.of("RETAIL"),
                                                "c",
                                                "uninstalled-policy",
                                                "proof")),
                                Map.of()));
    }

    private static BusinessEndDeploymentOption capability(String code, String entitlement, String route) {
        return new BusinessEndDeploymentOption(
                "ume-" + code, code, code, entitlement, List.of("FOOD"), "biz-app", route, "ume-acceptance-2026-11");
    }

    /** TDD-子行业与能力端验收 A1：行业 / 营销 / 连锁码是商户 App 里的功能，挂 BIZ 路由验收 */
    @Test
    void capabilityEndsRideOnTheBizRouteAndCanBeVerified() {
        for (String code : List.of("END_RETAIL", "END_FNB", "END_SERVICE", "END_MARKETING", "END_CHAIN")) {
            var option = capability(code, code, "commerce-route-v1:BIZ");
            var registry = new ShopMallDeploymentRegistry(List.of(option), Map.of());
            assertTrue(
                    registry.verified(
                            "MAIN",
                            new BusinessEndDraft(
                                    code,
                                    code,
                                    code,
                                    EndScope.STORE,
                                    List.of("FOOD"),
                                    ReleaseStatus.VERIFIED,
                                    false,
                                    false,
                                    true,
                                    "biz-app",
                                    "commerce-route-v1:BIZ",
                                    "ume-acceptance-2026-11",
                                    0,
                                    "publish")),
                    code);
        }
    }

    /** A2：白名单之外、挂 POS 路由、码与权益码不一致都拒绝 */
    @Test
    void capabilityEndsStayWithinTheirContract() {
        for (var bad :
                List.of(
                        capability("END_LOYALTY", "END_LOYALTY", "commerce-route-v1:BIZ"),
                        capability("END_FNB", "END_FNB", "commerce-route-v1:POS"),
                        capability("END_FNB", "END_RETAIL", "commerce-route-v1:BIZ"),
                        capability(CommerceRoutePolicy.POS, CommerceRoutePolicy.POS, "commerce-route-v1:BIZ"))) {
            assertThrows(
                    IllegalArgumentException.class,
                    () -> new ShopMallDeploymentRegistry(List.of(bad), Map.of()),
                    bad.code() + " via " + bad.routePolicyRef());
        }
    }
}
