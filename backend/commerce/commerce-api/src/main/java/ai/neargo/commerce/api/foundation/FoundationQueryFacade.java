package ai.neargo.commerce.api.foundation;

import ai.neargo.commerce.api.common.*;

import java.util.List;

/** Merchant read contract. Times are UTC epoch milliseconds. A null interval end is permanent. */
public interface FoundationQueryFacade {
    List<BusinessType> businessTypes(ActorContext actor);

    PlanCatalog plans(ActorContext actor, String typeCode);

    StoreContext store(ActorContext actor, StoreRef store);

    Subscription subscription(ActorContext actor, StoreRef store);

    Entitlements entitlements(ActorContext actor, StoreRef store);

    record BusinessType(String businessTypeCode, String displayName, long catalogVersion) {}

    record PlanCatalog(
            String businessTypeCode, Long catalogVersion, String status, List<PlanCard> plans) {}

    record PlanCard(
            String planNo,
            String planVersionNo,
            PlanTier tier,
            String displayName,
            String summary,
            List<String> entitlementCodes,
            String saleStatus,
            List<PriceOption> priceOptions,
            String priceStatus,
            boolean purchaseAvailable) {}

    record PriceOption(
            String skuNo,
            String skuVersionNo,
            String title,
            Money price,
            Money listPrice,
            int periodDays,
            int unitCount,
            boolean gift) {}

    record StoreContext(
            String storeNo,
            String entityNo,
            CommerceModel commerceModel,
            String businessTypeCode,
            String storeStatus,
            String entityStatus,
            boolean owner,
            List<String> roles,
            boolean canProcure,
            long serverNow,
            Long entitlementVersion,
            Long nextBoundaryAt,
            Subscription subscription,
            Entitlements entitlements) {}

    record Source(
            String sourceNo,
            String sourceType,
            String planNo,
            String planVersionNo,
            String tier,
            String displayName,
            boolean gift,
            Long planStartsAt,
            Long planEndsAt,
            Long grantedDurationMs,
            List<String> entitlementCodes,
            String lifecycle,
            long revokedDurationMs) {
        public Source(
                String sourceNo,
                String sourceType,
                String planNo,
                String planVersionNo,
                String tier,
                String displayName,
                boolean gift,
                Long planStartsAt,
                Long planEndsAt,
                Long grantedDurationMs,
                List<String> entitlementCodes) {
            this(
                    sourceNo,
                    sourceType,
                    planNo,
                    planVersionNo,
                    tier,
                    displayName,
                    gift,
                    planStartsAt,
                    planEndsAt,
                    grantedDurationMs,
                    entitlementCodes,
                    "NORMAL",
                    0);
        }
    }

    record Subscription(
            String storeNo,
            CommerceModel commerceModel,
            Long entitlementVersion,
            Source free,
            Source currentPlan,
            List<Source> sources,
            long serverNow,
            Long nextBoundaryAt,
            boolean purchaseAvailable,
            String nextAction) {}

    record Interval(long startsAt, Long endsAt) {}

    record Slice(String sourceNo, long startsAt, Long endsAt) {}

    record Right(
            String entitlementCode,
            String endCode,
            String commercialStatus,
            Interval current,
            List<Interval> future,
            List<Slice> slices,
            Long remainingMs,
            Long nextBoundaryAt,
            String nextAction) {}

    record Entitlements(
            String storeNo,
            CommerceModel commerceModel,
            Long entitlementVersion,
            List<Right> items,
            long serverNow,
            Long nextBoundaryAt) {}
}
