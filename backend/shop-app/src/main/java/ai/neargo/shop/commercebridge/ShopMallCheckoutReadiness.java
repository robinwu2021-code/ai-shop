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
        this.enabled = enabled;
        this.inventory = inventory;
        this.options = options;
        this.bindings = bindings;
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
}
