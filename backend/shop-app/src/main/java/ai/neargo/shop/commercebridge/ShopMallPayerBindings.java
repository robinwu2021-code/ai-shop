package ai.neargo.shop.commercebridge;

import ai.neargo.commerce.api.common.*;

import java.util.Optional;

/** Authoritative app-bound payer proofs. No historical raw-user lookup or client-body assertion. */
public interface ShopMallPayerBindings {
    record Verified(
            String reference,
            RawPrincipalIdentity identity,
            String appId,
            String providerIdentity) {}

    /** Pure local deployment capability only; no identity/database/network reads in readiness. */
    boolean available();

    Optional<Verified> current(ActorContext actor, String channel, String method);

    /** Immutable issued binding must remain recoverable after logout/configuration changes. */
    Optional<Verified> frozen(
            RawPrincipalIdentity identity,
            String canonicalBuyer,
            String channel,
            String method,
            String reference);

    ShopMallPayerBindings UNAVAILABLE =
            new ShopMallPayerBindings() {
                public boolean available() {
                    return false;
                }

                public Optional<Verified> current(ActorContext a, String c, String m) {
                    return Optional.empty();
                }

                public Optional<Verified> frozen(
                        RawPrincipalIdentity i, String b, String c, String m, String r) {
                    return Optional.empty();
                }
            };
}
