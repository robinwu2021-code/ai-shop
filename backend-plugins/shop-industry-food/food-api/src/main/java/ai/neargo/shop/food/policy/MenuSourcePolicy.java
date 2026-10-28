package ai.neargo.shop.food.policy;

import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.food.dto.ConfigRevisionContentVO;
import ai.neargo.shop.food.dto.MenuItemInput;
import ai.neargo.shop.food.dto.MenuPublicationSnapshot;
import ai.neargo.shop.food.dto.MenuSourceContracts.Kind;
import ai.neargo.shop.food.dto.MenuSourceContracts.Proof;
import ai.neargo.shop.food.dto.MenuSourceContracts.Query;
import ai.neargo.shop.food.dto.MenuSourceContracts.Resource;
import ai.neargo.shop.food.dto.PublicationSnapshot;
import ai.neargo.shop.spi.inventory.MaterialCatalogFacts;

import tools.jackson.databind.ObjectMapper;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Set;

/**
 * Projects frozen original identities; no mutable inheritance, price, stock or order qualification
 * is inferred.
 */
public final class MenuSourcePolicy {
    private MenuSourcePolicy() {}

    public static void validateQuery(Query q) {
        if (q == null
                || !key(q.entityNo())
                || !key(q.storeNo())
                || !key(q.menuNo())
                || !key(q.revisionNo())
                || q.kind() == null
                || !key(q.resourceRef())
                || q.mode() == null) throw BizException.of(ErrorCode.BAD_REQUEST);
        boolean sale = q.kind() != Kind.MENU && q.kind() != Kind.CATEGORY;
        if (sale && !key(q.salesOfferRef())
                || !sale && q.salesOfferRef() != null
                || q.kind() == Kind.OFFER && !q.resourceRef().equals(q.salesOfferRef())
                || q.kind() == Kind.OPTION && !key(q.optionGroupRef())
                || q.kind() != Kind.OPTION && q.optionGroupRef() != null)
            throw BizException.of(ErrorCode.BAD_REQUEST);
    }

    /** Revalidates original content even when invoked through an older remote read adapter. */
    public static Resource project(ObjectMapper json, Query q, ConfigRevisionContentVO source) {
        validateQuery(q);
        if (source == null
                || source.publication() == null
                || source.definitionSnapshot() == null
                || source.definitionSnapshot().length() > 1_048_576) throw malformed();
        var meta = source.publication();
        if (!q.revisionNo().equals(meta.revisionNo())
                || !"MENU".equals(meta.configKind())
                || !q.menuNo().equals(meta.configNo())
                || meta.revision() <= 0
                || meta.effectiveAt() == null
                || !"EFFECTIVE".equals(meta.status())
                || !PublicationDocuments.hash(json, source.definitionSnapshot())
                        .equals(meta.contentHash())
                || !Set.of(4, 6).contains(
                        MenuPublicationDocuments.validate(json, q.menuNo(), source.definitionSnapshot())))
            throw malformed();
        var body = json.readValue(source.definitionSnapshot(), MenuPublicationSnapshot.class);
        if (!q.entityNo().equals(body.menu().entityNo())
                || !q.storeNo().equals(body.menu().storeNo())) throw malformed();
        var categories = body.menu().definition().composition().categories();
        boolean member;
        if (q.kind() == Kind.MENU) member = q.menuNo().equals(q.resourceRef());
        else if (q.kind() == Kind.CATEGORY)
            member = categories.stream().anyMatch(c -> c.categoryNo().equals(q.resourceRef()));
        else {
            var item =
                    categories.stream()
                            .flatMap(c -> c.items().stream())
                            .filter(
                                    i ->
                                            Boolean.TRUE.equals(i.enabled())
                                                    && q.salesOfferRef().equals(i.menuItemNo()))
                            .findFirst()
                            .orElseThrow(() -> BizException.of(ErrorCode.NOT_FOUND));
            member =
                    switch (q.kind()) {
                        case OFFER -> true;
                        case SKU -> sku(body, item, q.resourceRef());
                        case OPTION_GROUP -> groups(body, item).contains(q.resourceRef());
                        case OPTION ->
                                groups(body, item).contains(q.optionGroupRef())
                                        && option(json, body, q);
                        default -> false;
                    };
        }
        if (!member) throw BizException.of(ErrorCode.NOT_FOUND);
        return new Resource(q.kind(), q.resourceRef(), q.salesOfferRef(), q.optionGroupRef());
    }

    private static boolean sku(MenuPublicationSnapshot body, MenuItemInput item, String number) {
        if ("EXPLICIT".equals(item.skuScope()) && !item.skuNos().contains(number)) return false;
        if ("ALL".equals(item.skuScope())
                && body.goodsFacts().stream()
                        .filter(g -> g.goodsNo().equals(item.goodsNo()))
                        .noneMatch(g -> g.skus().stream().anyMatch(s -> s.skuNo().equals(number))))
            return false;
        return body.skuFacts().stream()
                .anyMatch(s -> s.skuNo().equals(number) && s.goodsNo().equals(item.goodsNo()));
    }

    private static List<String> groups(MenuPublicationSnapshot body, MenuItemInput item) {
        return body.menu().itemOptions().stream()
                .filter(i -> i.menuItemNo().equals(item.menuItemNo()))
                .findFirst()
                .orElseThrow(MenuSourcePolicy::malformed)
                .optionGroupNos();
    }

    private static boolean option(ObjectMapper json, MenuPublicationSnapshot body, Query q) {
        var dependency =
                body.menu().dependencies().stream()
                        .filter(
                                d ->
                                        "OPTION_GROUP".equals(d.configKind())
                                                && q.optionGroupRef().equals(d.configNo()))
                        .findFirst()
                        .orElseThrow(MenuSourcePolicy::malformed);
        var group =
                json.readValue(dependency.definitionSnapshot(), PublicationSnapshot.class)
                        .optionGroup();
        return group.data().items().stream()
                .anyMatch(i -> q.resourceRef().equals(i.optionItemNo()));
    }

    /**
     * This checksum binds the complete original read result; it is not a signature or a payment
     * credential.
     */
    public static String receipt(ObjectMapper json, Proof proof) {
        var unsigned =
                new Proof(
                        proof.schemaVersion(),
                        proof.query(),
                        proof.publication(),
                        proof.resource(),
                        proof.verifiedAt(),
                        proof.validUntil(),
                        null);
        return PublicationDocuments.hash(json, json.writeValueAsString(unsigned));
    }

    public static void validateProof(
            ObjectMapper json, Query query, Proof proof, OffsetDateTime now) {
        try {
            validateQuery(query);
            if (proof == null
                    || proof.schemaVersion() != 1
                    || !query.equals(proof.query())
                    || proof.verifiedAt() == null
                    || proof.validUntil() == null
                    || proof.verifiedAt().isAfter(now)
                    || !proof.validUntil().isAfter(now)
                    || !proof.validUntil().isAfter(proof.verifiedAt())
                    || proof.validUntil().isAfter(proof.verifiedAt().plusSeconds(30))
                    || !project(json, query, proof.publication()).equals(proof.resource())
                    || proof.publication().publication().effectiveAt().isAfter(proof.verifiedAt())
                    || !receipt(json, proof).equals(proof.receiptNo())) throw malformed();
        } catch (RuntimeException failure) {
            throw new IllegalStateException("Invalid menu source proof", failure);
        }
    }

    private static boolean key(String value) {
        return MaterialCatalogFacts.validKey(value, 32);
    }

    private static IllegalStateException malformed() {
        return new IllegalStateException("Invalid menu source facts");
    }
}
