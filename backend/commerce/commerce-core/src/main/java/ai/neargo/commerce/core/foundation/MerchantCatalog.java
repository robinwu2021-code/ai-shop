package ai.neargo.commerce.core.foundation;

import ai.neargo.commerce.api.catalog.*;
import ai.neargo.commerce.api.common.*;
import ai.neargo.commerce.api.foundation.FoundationQueryFacade.*;
import ai.neargo.commerce.core.catalog.CatalogState;

import java.util.*;

final class MerchantCatalog {
    private MerchantCatalog() {}

    static List<BusinessType> types(CatalogState state) {
        return state.types.values().stream()
                .filter(t -> current(state, t).size() == PlanTier.values().length)
                .sorted(
                        Comparator.comparingInt((CatalogState.Type t) -> t.draft().sortOrder())
                                .thenComparing(t -> t.draft().typeCode()))
                .map(
                        t ->
                                new BusinessType(
                                        t.draft().typeCode(), t.draft().name(), t.catalogVersion()))
                .toList();
    }

    private static Map<PlanTier, PlanDraft> current(CatalogState state, CatalogState.Type type) {
        var rows = new EnumMap<PlanTier, PlanDraft>(PlanTier.class);
        if (type == null || !type.draft().enabled() || type.catalogVersion() <= 0) return rows;
        for (var p : state.plans.values()) {
            if (!p.typeCode().equals(type.draft().typeCode())
                    || !Set.of("ON_SALE", "OFF_SALE").contains(p.saleStatus())
                    || p.tier() == PlanTier.FREE && !"ON_SALE".equals(p.saleStatus())) continue;
            var v = state.planVersions.get(p.currentVersionNo());
            if (v == null || !"PUBLISHED".equals(v.status())) continue;
            var d = v.draft();
            if (!d.planNo().equals(p.planNo())
                    || d.tier() != p.tier()
                    || !d.typeCode().equals(p.typeCode())
                    || rows.put(p.tier(), d) != null) return Map.of();
        }
        return rows;
    }

    static PlanCatalog plans(CatalogState state, String code) {
        var type = state.types.get(code);
        var current = current(state, type);
        if (current.size() != PlanTier.values().length)
            return new PlanCatalog(code, null, "UNAVAILABLE", List.of());
        var cards = new ArrayList<PlanCard>();
        for (var d : current.values()) {
            String sale = state.plans.get(d.planNo()).saleStatus();
            var prices = "ON_SALE".equals(sale) ? prices(state, d) : List.<PriceOption>of();
            String status =
                    d.tier() == PlanTier.FREE
                            ? "INCLUDED"
                            : "OFF_SALE".equals(sale)
                                    ? "OFF_SALE"
                                    : prices.isEmpty() ? "UNCONFIGURED" : "DISPLAY_ONLY";
            cards.add(
                    new PlanCard(
                            d.planNo(),
                            d.planVersionNo(),
                            d.tier(),
                            d.displayName(),
                            d.summary(),
                            d.entitlementCodes(),
                            sale,
                            prices,
                            status,
                            false));
        }
        return new PlanCatalog(code, type.catalogVersion(), "PUBLISHED", List.copyOf(cards));
    }

    private static List<PriceOption> prices(CatalogState state, PlanDraft plan) {
        if (plan.tier() == PlanTier.FREE) return List.of();
        var result = new ArrayList<PriceOption>();
        for (var sku : state.skus.values()) {
            var p = state.products.get(sku.productNo());
            var v = state.skuVersions.get(sku.currentVersionNo());
            if (!"ON_SALE".equals(sku.saleStatus())
                    || p == null
                    || !"ON_SALE".equals(p.saleStatus())
                    || p.draft().kind() != ProductKind.PLAN
                    || !p.draft().allowedBusinessTypes().contains(plan.typeCode())
                    || v == null
                    || !"PUBLISHED".equals(v.status())) continue;
            var d = v.draft();
            if (!sku.skuNo().equals(d.skuNo())
                    || !sku.productNo().equals(d.productNo())
                    || !d.allowedBusinessTypes().contains(plan.typeCode())
                    || d.components().size() != 1) continue;
            var c = d.components().getFirst();
            if (c.kind() == ComponentKind.PLAN && plan.planVersionNo().equals(c.planVersionNo())) {
                result.add(
                        new PriceOption(
                                d.skuNo(),
                                d.skuVersionNo(),
                                d.title(),
                                d.price(),
                                d.listPrice(),
                                c.periodDays(),
                                c.unitCount(),
                                c.gift()));
            }
        }
        result.sort(Comparator.comparing(PriceOption::skuNo));
        return List.copyOf(result);
    }
}
