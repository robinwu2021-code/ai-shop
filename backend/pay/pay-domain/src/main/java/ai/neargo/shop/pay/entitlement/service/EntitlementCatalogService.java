package ai.neargo.shop.pay.entitlement.service;

import ai.neargo.shop.common.PageData;
import ai.neargo.shop.pay.entitlement.dto.CatalogScope;
import ai.neargo.shop.pay.entitlement.dto.CatalogViews.PublishedVersionView;
import ai.neargo.shop.pay.entitlement.dto.CatalogViews.TemplateView;
import ai.neargo.shop.spi.entitlement.EntitlementDefinition;

/** Payment-owned definitions; all writes require a stable request key and an authenticated scope. */
public interface EntitlementCatalogService {
    /** Create a draft, replaying the original response for the same key and payload. */
    TemplateView create(CatalogScope scope, EntitlementDefinition draft, String requestKey);

    /** Edit the draft only; kind, funder and published currency cannot change. */
    TemplateView saveDraft(CatalogScope scope, String templateNo, long expectedRevision,
                           EntitlementDefinition draft, String requestKey);

    /** Atomically publish a new immutable version and advance the owned head. */
    PublishedVersionView publish(CatalogScope scope, String templateNo, long expectedRevision, String requestKey);

    /** Pausing/ending issuance never revokes historical definitions. */
    TemplateView changeStatus(CatalogScope scope, String templateNo, long expectedRevision,
                              String status, String requestKey);

    /** Scoped management read, never an unguarded lookup by business number. */
    TemplateView get(CatalogScope scope, String templateNo);

    /** Read a historical published definition, including while its head is paused/ended. */
    PublishedVersionView getVersion(CatalogScope scope, String versionNo);

    /** Database-paginated owned templates; no cross-owner list fallback. */
    PageData<TemplateView> list(CatalogScope scope, String kind, String status, long page, long size);
}
