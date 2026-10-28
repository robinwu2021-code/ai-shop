package ai.neargo.shop.food.commerce.policy;

import ai.neargo.shop.commerce.policy.ChannelCatalogAuthorityPolicy;
import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.food.commerce.dto.FoodCatalogBindingContracts;
import ai.neargo.shop.food.commerce.dto.FoodCatalogPricePolicyContracts.Facts;
import ai.neargo.shop.food.commerce.dto.FoodCatalogPricePolicyContracts.Query;
import ai.neargo.shop.food.dto.MenuCatalogPriceContracts;
import ai.neargo.shop.food.dto.MenuPublicationSnapshot;
import ai.neargo.shop.food.dto.MenuSourceContracts.Kind;
import ai.neargo.shop.food.dto.MenuSourceContracts.Mode;
import ai.neargo.shop.food.policy.MenuCatalogPricePolicy;
import ai.neargo.shop.food.policy.MenuSourcePolicy;
import ai.neargo.shop.food.policy.PublicationDocuments;
import ai.neargo.shop.spi.inventory.MaterialCatalogFacts;

import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.time.ZoneOffset;

/** Revalidates all original components so a newly computed checksum cannot authorize altered prices. */
public final class FoodCatalogPricePolicy {
    public static final String REF = "MENU_PUBLISHED_PRICES_V1";
    public static final String MODE = "MENU_OVERRIDE_THEN_CATALOG_BASE";

    private FoodCatalogPricePolicy() {}

    public static void validateQuery(Query q) {
        if (q == null || q.binding() == null || !REF.equals(q.pricePolicyRef())
                || !MaterialCatalogFacts.validKey(q.binding().bindingNo(), 32)) {
            throw BizException.of(ErrorCode.BAD_REQUEST);
        }
        var b = q.binding();
        MenuSourcePolicy.validateQuery(b.menu());
        ChannelCatalogAuthorityPolicy.validateQuery(b.connection());
        if (b.menu().kind() != Kind.MENU || b.menu().mode() != Mode.CURRENT
                || !b.menu().menuNo().equals(b.menu().resourceRef())
                || !b.menu().entityNo().equals(b.connection().entityNo())
                || !b.menu().storeNo().equals(b.connection().storeNo())) throw BizException.of(ErrorCode.BAD_REQUEST);
    }

    public static MenuCatalogPriceContracts.Query priceQuery(Query q) {
        var m = q.binding().menu();
        return new MenuCatalogPriceContracts.Query(m.entityNo(), m.storeNo(), m.menuNo(), m.revisionNo());
    }

    public static Facts seal(ObjectMapper json, Facts f) {
        var unsigned = new Facts(f.schemaVersion(), f.query(), f.policyVersion(), f.mode(),
                f.bindingFacts(), f.prices(), f.verifiedAt(), f.validUntil(), null);
        return new Facts(f.schemaVersion(), f.query(), f.policyVersion(), f.mode(),
                f.bindingFacts(), f.prices(), f.verifiedAt(), f.validUntil(),
                PublicationDocuments.hash(json, json.writeValueAsString(unsigned)));
    }

    public static void validateBinding(
            ObjectMapper json, FoodCatalogBindingContracts.Query q,
            FoodCatalogBindingContracts.Facts f, Instant now) {
        require(f != null && q.equals(f.query()) && now != null);
        MenuSourcePolicy.validateProof(json, q.menu(), f.menuProof(), now.atOffset(ZoneOffset.UTC));
        ChannelCatalogAuthorityPolicy.validate(q.connection(), f.authority(), now);
        var body = json.readValue(f.menuProof().publication().definitionSnapshot(), MenuPublicationSnapshot.class);
        var bindings = body.menu().channelBindings().stream().filter(b -> q.bindingNo().equals(b.bindingNo())).toList();
        var contexts = body.menu().definition().contexts().stream()
                .filter(c -> q.bindingNo().equals(c.channelBindingNo())).toList();
        require(bindings.size() == 1 && contexts.size() == 1
                && bindings.getFirst().equals(f.binding()) && contexts.getFirst().equals(f.context()));
        var b = f.binding();
        var a = f.authority();
        require(q.menu().menuNo().equals(b.menuNo()) && q.menu().storeNo().equals(b.storeNo())
                && "ACTIVE".equals(b.status()) && b.provider().equals(a.provider().providerCode())
                && b.channelAccountRef().equals(a.account().externalAccountRef())
                && b.externalStoreRef().equals(a.connection().externalStoreRef())
                && "FOOD".equals(a.connection().sourceOwner())
                && "LOCAL".equals(a.connection().catalogAuthority())
                && "INSTANT".equals(a.connection().fulfillmentProfile())
                && "CHANNEL".equals(f.context().orderSource()) && "DELIVERY".equals(f.context().fulfillment())
                && f.verifiedAt() != null && !f.verifiedAt().isAfter(now)
                && !f.verifiedAt().isBefore(f.menuProof().verifiedAt().toInstant())
                && !f.verifiedAt().isBefore(a.verifiedAt()) && f.validUntil() != null
                && f.validUntil().isAfter(now) && f.validUntil().isAfter(f.verifiedAt())
                && !f.validUntil().isAfter(f.menuProof().validUntil().toInstant())
                && !f.validUntil().isAfter(a.validUntil()));
    }

    public static void validate(ObjectMapper json, Query q, Facts f, Instant now) {
        validateQuery(q);
        require(f != null && f.schemaVersion() == 1 && q.equals(f.query())
                && f.policyVersion() == 1 && MODE.equals(f.mode()));
        validateBinding(json, q.binding(), f.bindingFacts(), now);
        MenuCatalogPricePolicy.validate(json, priceQuery(q), f.prices(), now);
        var b = f.bindingFacts();
        require(q.pricePolicyRef().equals(b.context().pricePolicyRef())
                && b.menuProof().publication().equals(f.prices().menuProof().publication())
                && b.authority().profile().market().equals(f.prices().productFacts().market().market())
                && f.verifiedAt() != null && !f.verifiedAt().isAfter(now)
                && !f.verifiedAt().isBefore(b.verifiedAt())
                && !f.verifiedAt().isBefore(f.prices().verifiedAt())
                && f.validUntil() != null && f.validUntil().isAfter(now)
                && f.validUntil().isAfter(f.verifiedAt())
                && !f.validUntil().isAfter(b.validUntil()) && !f.validUntil().isAfter(f.prices().validUntil())
                && !f.validUntil().isAfter(f.verifiedAt().plusSeconds(30))
                && seal(json, f).receiptNo().equals(f.receiptNo()));
    }

    public static void require(boolean value) {
        if (!value) throw new IllegalStateException("Invalid original FOOD price policy facts");
    }
}
