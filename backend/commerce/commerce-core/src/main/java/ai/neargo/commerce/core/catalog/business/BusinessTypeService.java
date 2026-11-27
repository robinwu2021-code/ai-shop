package ai.neargo.commerce.core.catalog.business;

import static ai.neargo.commerce.core.catalog.CatalogSupport.*;

import ai.neargo.commerce.api.catalog.*;
import ai.neargo.commerce.api.common.*;
import ai.neargo.commerce.core.catalog.*;

public final class BusinessTypeService {
    private final CatalogSupport s;

    private final boolean dictionary;

    public BusinessTypeService(CatalogSupport s, boolean dictionary) {
        this.dictionary = dictionary;
        this.s = s;
    }

    public DraftSaveResult save(ActorContext a, BusinessTypeDraft d) {
        code(d.typeCode(), 32);
        text(d.name(), 64, "name");
        if (dictionary) {
            s.authorizeWrite(a, null);
            text(d.reason(), 1024, "reason");
            var old = s.repository.businessTypes(a.tenantNo()).stream()
                    .filter(t -> t.draft().typeCode().equals(d.typeCode())).findFirst().orElse(null);
            var next = updated(a, d, old);
            s.repository.saveBusinessType(a.tenantNo(), old, next,
                    new CatalogWriteContext(a.principalNo(), s.clock.instant()));
            return new DraftSaveResult(d.typeCode(), null, next.version());
        }
        return s.write(a, null, d.reason(), "TYPE", d.typeCode(), "TYPE_SAVED", null, state -> {
            var old = state.types.get(d.typeCode());
            var next = updated(a, d, old);
            state.types.put(d.typeCode(), next);
            if (old == null)
                for (var tier : PlanTier.catalogTiers(d.typeCode())) {
                    var no = d.typeCode() + ":" + tier;
                    state.plans.put(
                            no,
                            new CatalogState.Plan(
                                    no, d.typeCode(), tier, null, "DRAFT", 1));
                }
            return new DraftSaveResult(d.typeCode(), null, next.version());
        });
    }

    private CatalogState.Type updated(ActorContext a, BusinessTypeDraft d, CatalogState.Type old) {
        expected(d.expectedVersion(), old == null ? 0 : old.version());
        if (old == null) {
            s.authorize(a, "edit");
            check(
                    !d.enabled(),
                    "create type disabled; publish industry before enabling");
        } else {
            if (!old.draft().name().equals(d.name())
                    || old.draft().sortOrder() != d.sortOrder()) s.authorize(a, "edit");
            if (old.draft().enabled() != d.enabled()) s.authorize(a, "publish");
            check(
                    !d.enabled() || old.catalogVersion() > 0,
                    "publish industry before enabling");
        }
        long version = old == null ? 1 : old.version() + 1;
        return new CatalogState.Type(d, old == null ? 0 : old.catalogVersion(), version);
    }
}
