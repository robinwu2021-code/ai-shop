package ai.neargo.shop.commercebridge;

import ai.neargo.commerce.api.catalog.*;
import ai.neargo.commerce.api.common.*;
import ai.neargo.commerce.api.port.CheckoutReadinessPort;

import java.util.*;
import java.util.function.Supplier;

/** Called under commercial locks: flags, immutable mappings and local gateway metadata only. */
final class ShopMallCheckoutReadiness implements CheckoutReadinessPort {
    private final boolean enabled;
    private final ShopMallHardwareInventoryAdapter inventory;
    private final Supplier<List<PaymentOption>> options;
    private final ShopMallPayerBindings bindings;
    private final boolean offlineRouteAllowed;
    private final String recipient;
    private static final long MAX_SAFE_MINOR = 9007199254740991L;

    ShopMallCheckoutReadiness(
            boolean enabled,
            ShopMallHardwareInventoryAdapter inventory,
            Supplier<List<PaymentOption>> options) {
        this(enabled, inventory, options, ShopMallPayerBindings.UNAVAILABLE);
    }

    ShopMallCheckoutReadiness(
            boolean enabled,
            ShopMallHardwareInventoryAdapter inventory,
            Supplier<List<PaymentOption>> options,
            ShopMallPayerBindings bindings) {
        this(enabled, inventory, options, bindings, false, null);
    }

    ShopMallCheckoutReadiness(
            boolean enabled,
            ShopMallHardwareInventoryAdapter inventory,
            Supplier<List<PaymentOption>> options,
            ShopMallPayerBindings bindings,
            boolean offlineRouteAllowed,
            String recipient) {
        this.enabled = enabled;
        this.inventory = inventory;
        this.options = options;
        this.bindings = bindings;
        this.offlineRouteAllowed = offlineRouteAllowed && recipient != null && !recipient.isBlank();
        this.recipient = recipient;
    }

    public Readiness evaluate(StoreSnapshot store, Money payable, List<SkuDraft> skus) {
        var reasons = new LinkedHashSet<String>();
        if (!enabled) reasons.add("PAYMENT_COORDINATION_DISABLED");
        if (store.model() != CommerceModel.STORE_V1 || store.status() != StoreStatus.ACTIVE)
            reasons.add("STORE_UNAVAILABLE");
        for (var sku : skus)
            for (var component : sku.components())
                if (component.kind() == ComponentKind.HARDWARE
                        && !inventory.mapped(component.hardwareSkuNo()))
                    reasons.add("INVENTORY_MAPPING_UNAVAILABLE");
        var available =
                options.get().stream()
                        .filter(
                                p ->
                                        p.currency().equals(payable.currency())
                                                && payable.minor() <= p.maximumMinor())
                        .toList();
        if (payable.minor() > 0 && !bindings.available()) reasons.add("PAYER_BINDING_UNPROVEN");
        if (payable.minor() > 0 && available.isEmpty()) reasons.add("PAYMENT_ROUTE_UNAVAILABLE");
        return new Readiness(
                reasons.isEmpty(),
                List.copyOf(reasons),
                payable.minor() == 0 ? List.of() : available);
    }

    /**
     * Bank transfer needs no payer binding or gateway capability, but it does need the explicit
     * OFFLINE:BANK_TRANSFER route, matching published terms, and a positive amount (zero cash uses
     * the internal settlement, never a fabricated transfer).
     */
    public Readiness evaluateOffline(
            StoreSnapshot store,
            Money payable,
            List<SkuDraft> skus,
            ai.neargo.commerce.api.offline.OfflineCollectionTerms terms) {
        var reasons = new LinkedHashSet<String>();
        if (!enabled) reasons.add("PAYMENT_COORDINATION_DISABLED");
        if (!offlineRouteAllowed || terms == null) reasons.add("OFFLINE_UNAVAILABLE");
        if (store.model() != CommerceModel.STORE_V1 || store.status() != StoreStatus.ACTIVE)
            reasons.add("STORE_UNAVAILABLE");
        for (var sku : skus)
            for (var component : sku.components())
                if (component.kind() == ComponentKind.HARDWARE
                        && !inventory.mapped(component.hardwareSkuNo()))
                    reasons.add("INVENTORY_MAPPING_UNAVAILABLE");
        if (terms != null
                && (!terms.currency().equals(payable.currency())
                        || !terms.platformMerchantNo().equals(recipient)))
            reasons.add("OFFLINE_PROFILE_MISMATCH");
        if (payable.minor() <= 0) reasons.add("OFFLINE_NOT_REQUIRED");
        var options =
                reasons.isEmpty()
                        ? List.of(
                                new PaymentOption(
                                        "OFFLINE",
                                        "BANK_TRANSFER",
                                        payable.currency(),
                                        MAX_SAFE_MINOR))
                        : List.<PaymentOption>of();
        return new Readiness(reasons.isEmpty(), List.copyOf(reasons), options);
    }
}
