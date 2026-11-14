package ai.neargo.commerce.core.catalog.end;

import static ai.neargo.commerce.core.catalog.CatalogSupport.*;

import ai.neargo.commerce.api.catalog.*;
import ai.neargo.commerce.api.common.*;
import ai.neargo.commerce.core.catalog.*;

import java.util.*;

public final class BusinessEndService {
    private final CatalogSupport s;

    public BusinessEndService(CatalogSupport s) {
        this.s = s;
    }

    public DraftSaveResult save(ActorContext a, BusinessEndDraft d) {
        s.authorize(a, "edit");
        code(d.code(), 64);
        code(d.entitlementCode(), 64);
        text(d.name(), 64, "name");
        check(d.scope() != null, "end scope required");
        check(d.releaseStatus() != null, "end release status required");
        unique(d.allowedBusinessTypes());
        check(!d.allowedBusinessTypes().isEmpty(), "end type scope required");
        check(d.scope() == EndScope.STORE || !d.saleEnabled(), "SYSTEM end cannot be sold");
        check(!d.bootstrapRequired() || d.scope() == EndScope.STORE, "base end must be STORE");
        if (d.releaseStatus() == ReleaseStatus.VERIFIED) {
            text(d.clientId(), 128, "clientId");
            text(d.routePolicyRef(), 128, "routePolicyRef");
            text(d.acceptanceRef(), 128, "acceptanceRef");
            check(s.readiness.verified(a.tenantNo(), d), "trusted readiness proof rejected");
        }
        return s.write(
                a,
                "edit",
                d.reason(),
                "END",
                d.code(),
                "END_SAVED",
                null,
                state -> {
                    d.allowedBusinessTypes().forEach(t -> require(state.types, t));
                    var old = state.ends.get(d.code());
                    expected(d.expectedVersion(), old == null ? 0 : old.version());
                    check(
                            state.ends.values().stream()
                                    .noneMatch(
                                            e ->
                                                    !e.draft().code().equals(d.code())
                                                            && e.draft()
                                                                    .entitlementCode()
                                                                    .equals(d.entitlementCode())),
                            "duplicate entitlement binding");
                    if (old != null) {
                        var o = old.draft();
                        check(
                                o.entitlementCode().equals(d.entitlementCode()),
                                "stable entitlement binding cannot change");
                        boolean used =
                                state.planVersions.values().stream()
                                                .anyMatch(
                                                        v ->
                                                                v.draft()
                                                                        .entitlementCodes()
                                                                        .contains(
                                                                                o
                                                                                        .entitlementCode()))
                                        || state.skuVersions.values().stream()
                                                .anyMatch(
                                                        v ->
                                                                v.draft().components().stream()
                                                                        .anyMatch(
                                                                                c ->
                                                                                        o.entitlementCode()
                                                                                                .equals(
                                                                                                        c
                                                                                                                .entitlementCode())));
                        check(
                                !used
                                        || (o.scope() == d.scope()
                                                && o.allowedBusinessTypes()
                                                        .equals(d.allowedBusinessTypes())
                                                && o.bootstrapRequired() == d.bootstrapRequired()),
                                "referenced end scope/base binding is immutable");
                    }
                    long v = old == null ? 1 : old.version() + 1;
                    state.ends.put(d.code(), new CatalogState.End(d, v));
                    return new DraftSaveResult(d.code(), null, v);
                });
    }
}
