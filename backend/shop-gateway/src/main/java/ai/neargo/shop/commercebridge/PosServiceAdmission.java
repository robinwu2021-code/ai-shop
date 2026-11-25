package ai.neargo.shop.commercebridge;

import ai.neargo.commerce.api.access.AccessDecision;
import ai.neargo.commerce.api.access.AccessDenied;
import ai.neargo.commerce.api.access.AccessStatus;
import ai.neargo.commerce.api.common.StoreSnapshot;
import ai.neargo.commerce.api.foundation.FoundationQueryFacade;
import ai.neargo.shop.portal.biz.feature.PosFeatureCatalogStore;
import ai.neargo.shop.portal.biz.feature.StoreFeature;
import ai.neargo.shop.portal.biz.feature.StoreFeatureMatrix;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.util.Set;

/** Commercial gate for service APIs. Existing transaction reconciliation stays on its existing authorization path. */
@Component
@Profile("api")
@ConditionalOnProperty(name = "commerce.enabled", havingValue = "true")
class PosServiceAdmission {
    private static final String BEAUTY = "/biz/x/beauty/";
    private static final Set<String> BOOKING = Set.of(
            "appointments", "availability", "arrival-challenges", "arrivals", "walk-ins", "reception-exceptions",
            "guest-registrations", "reports", "calendar");
    private static final Set<String> CATALOG = Set.of("services", "service-definitions", "store-policies");

    private final FoundationQueryFacade foundation;
    private final PosFeatureCatalogStore releases;
    private final boolean enabled;

    PosServiceAdmission(FoundationQueryFacade foundation, PosFeatureCatalogStore releases,
                        @Value("${shop.pos.feature-catalog.enforce-service-entitlements:false}") boolean enabled) {
        this.foundation = foundation;
        this.releases = releases;
        this.enabled = enabled;
    }

    void require(String method, String path, StoreSnapshot store) {
        if (!enabled) return;
        String featureCode = featureCode(method, path, store.businessTypeCode());
        if (featureCode == null) return;
        var actor = ShopCommerceActorAdapter.current();
        var release = releases.current(actor.tenantNo());
        var feature = release.feature(featureCode);
        if (feature == null || feature.lifecycle() != StoreFeature.Lifecycle.ACTIVE
                || !feature.appliesTo(store.businessTypeCode())) {
            throw new AccessDenied(AccessStatus.FORBIDDEN);
        }
        var context = foundation.store(actor, store.store());
        if (!feature.entitlement().matches(StoreFeatureMatrix.activeRights(context))) {
            throw AccessDenied.withNextAction(AccessStatus.NOT_PURCHASED,
                    AccessDecision.nextActionFor(AccessStatus.NOT_PURCHASED, context.canProcure()));
        }
    }

    static String featureCode(String method, String path, String industry) {
        if (path.startsWith(BEAUTY)) {
            String resource = path.substring(BEAUTY.length()).split("/", 2)[0];
            if (BOOKING.contains(resource)) return "SVC_BOOKING";
            if (CATALOG.contains(resource)) return "SVC_CATALOG";
            if ((BEAUTY + "work-orders/my-tasks").equals(path)) return "SVC_TASK";
            if ("work-orders".equals(resource) && path.equals(BEAUTY + resource) && "POST".equals(method)) {
                return "SVC_TASK";
            }
        }
        if ("POST".equals(method) && path.equals("/biz/service-orders/counter-quotes")) return "SVC_COUNTER_CASH";
        if (!"SERVICE".equals(industry)) return null;
        if (path.matches("/biz/(?:personnel|resources|schedules|schedule-rules)(?:/.*)?")) return "SVC_SCHEDULE";
        if (path.matches("/biz/(?:stores/[^/]+/appointment-slots|appointment-slots/[^/]+/close)")) {
            return "SVC_CATALOG";
        }
        return null;
    }
}
