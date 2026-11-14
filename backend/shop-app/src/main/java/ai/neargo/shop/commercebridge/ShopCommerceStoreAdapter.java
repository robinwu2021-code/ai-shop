package ai.neargo.shop.commercebridge;

import ai.neargo.commerce.api.access.AccessDenied;
import ai.neargo.commerce.api.access.AccessStatus;
import ai.neargo.commerce.api.common.*;
import ai.neargo.commerce.api.port.StoreDirectoryPort;
import ai.neargo.commerce.starter.security.CommerceRoutePolicy.Lane;
import ai.neargo.commerce.starter.security.CommerceRoutePolicy.Route;
import ai.neargo.shop.auth.BizContext;
import ai.neargo.shop.auth.BizIdentityResolver;
import ai.neargo.shop.auth.BizPerms;
import ai.neargo.shop.auth.LoginUser;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.*;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;

import javax.sql.DataSource;

/** Onboarding uses its separate authority to read its own uncommitted rows. */
final class ShopCommerceStoreAdapter implements StoreDirectoryPort {
    // Match the exact Java enum spelling under both MySQL and H2 collations. NULL is pre-model
    // LEGACY. The length check also rejects values with extra padding.
    private static final String NON_LEGACY_MODEL =
            "(OCTET_LENGTH(commerce_model) <> 6 OR CAST(commerce_model AS BINARY(6)) <>"
                    + " CAST('LEGACY' AS BINARY(6)))";
    private final JdbcTemplate jdbc;
    private final TransactionTemplate fresh;
    private final ai.neargo.shop.auth.store.FreshAccountReadDataSource routing;

    ShopCommerceStoreAdapter(DataSource ds, PlatformTransactionManager manager) {
        jdbc = new JdbcTemplate(ds);
        routing = ds instanceof ai.neargo.shop.auth.store.FreshAccountReadDataSource source ? source : null;
        fresh = new TransactionTemplate(manager);
        fresh.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        fresh.setReadOnly(true);
        fresh.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
    }

    private <T> T freshRead(java.util.function.Supplier<T> action) {
        return routing == null ? fresh.execute(tx -> action.get()) : routing.read(fresh, action);
    }

    StoreSnapshot current(StoreRef ref) {
        return freshRead(() -> find(ref).filter(s -> ref.equals(s.store())).orElseThrow());
    }

    FoundationTarget legacyFinanceTarget(
            LoginUser user, String currentEntity, String storeNo, BizIdentityResolver resolver) {
        return freshRead(
                () -> {
                    var ref = new StoreRef(user.tenantNo(), storeNo);
                    var store =
                            find(ref)
                                    .filter(
                                            s ->
                                                    ref.equals(s.store())
                                                            && s.model() == CommerceModel.LEGACY
                                                            && currentEntity.equals(s.merchantNo()))
                                    .orElseThrow(() -> new AccessDenied(AccessStatus.FORBIDDEN));
                    if (store.status() != StoreStatus.ACTIVE)
                        throw new AccessDenied(AccessStatus.STORE_DISABLED);
                    var scope = resolver.resolveStrict(user, storeNo);
                    if (!storeNo.equals(scope.currentStoreNo())
                            || !currentEntity.equals(scope.merchantNo())
                            || !scope.can(BizPerms.FINANCE))
                        throw new AccessDenied(AccessStatus.FORBIDDEN);
                    return new FoundationTarget(scope.merchantNo(), storeNo);
                });
    }

    /** Actual targets of the legacy member query, read independently of the handler transaction. */
    List<StoreSnapshot> memberQueryTargets(String tenantNo, String entityNo, String targetStoreNo) {
        return freshRead(() -> {
            String target = targetStoreNo == null || targetStoreNo.isBlank() ? null : targetStoreNo;
            List<StoreFacts> targets;
            try {
                targets = jdbc.query("""
SELECT s.store_no,s.entity_no,s.business_type_code,s.commerce_model,s.status,m.status
FROM mch_store s LEFT JOIN mch_entity m ON m.tenant_no=s.tenant_no AND m.entity_no=s.entity_no AND m.deleted=0
WHERE s.tenant_no=? AND s.entity_no=? AND s.deleted=0 AND (? IS NULL OR s.store_no=?)
""", (rs, i) -> readFacts(tenantNo, rs), tenantNo, entityNo, target, target);
            } catch (IllegalArgumentException unknownModel) {
                throw new AccessDenied(AccessStatus.CHECK_UNAVAILABLE);
            }
            if (target != null && targets.isEmpty())
                throw new AccessDenied(AccessStatus.FORBIDDEN);
            return targets.stream().map(StoreFacts::snapshot)
                    .filter(f -> f.model() != CommerceModel.LEGACY).toList();
        });
    }

    boolean hasPersistedNewStores() {
        return Boolean.TRUE.equals(
                jdbc.queryForObject(
                        "SELECT EXISTS(SELECT 1 FROM mch_store WHERE deleted=0 AND "
                                + NON_LEGACY_MODEL
                                + ")",
                        Boolean.class));
    }

    boolean affects(LoginUser user, BizContext scope, Route route, String requested) {
        return merchantScopeIncludesNewModel(user, scope.merchantNo(), route.lane())
                || java.util.stream.Stream.of(requested, scope.currentStoreNo(), route.pathStore())
                        .filter(java.util.Objects::nonNull)
                        .distinct()
                        .anyMatch(
                                no ->
                                        find(new StoreRef(user.tenantNo(), no))
                                                .map(s -> s.model() != CommerceModel.LEGACY)
                                                .orElse(false));
    }

    /** The B-app uses "default" for the existing merchant-scoped preview. */
    BizContext legacyPreviewScope(
            LoginUser user, BizContext scope, String requested, String pathStore) {
        String target = "default".equals(pathStore) ? scope.currentStoreNo() : pathStore;
        if (target == null
                || !scope.storeNos().contains(target)
                || requested != null && !requested.isBlank() && !requested.equals(target)
                || merchantScopeIncludesNewModel(user, scope.merchantNo(), Lane.LEGACY_PREVIEW))
            throw new AccessDenied(AccessStatus.FORBIDDEN);
        find(new StoreRef(user.tenantNo(), target))
                .filter(
                        s ->
                                s.model() == CommerceModel.LEGACY
                                        && s.merchantNo().equals(scope.merchantNo()))
                .orElseThrow(() -> new AccessDenied(AccessStatus.FORBIDDEN));
        return scope.withStore(target);
    }

    // Legacy handlers can use the selected entity's entire store list, including unassigned
    // or suspended siblings. A role limits authority, not the set of possible handler targets.
    private boolean merchantScopeIncludesNewModel(
            LoginUser user, String selectedMerchant, Lane lane) {
        String key =
                switch (user.subjectKind()) {
                    case USR -> "a.user_no";
                    case MCH -> "a.mch_account_no";
                    default -> throw new IllegalArgumentException("Merchant account kind required");
                };
        // An unaudited handler may repeat its lookup with just the bare principal. Include
        // both namespaces when proving legacy fallthrough safe. This query can only deny.
        String keys =
                lane == Lane.DENY || lane == Lane.LEGACY_PREVIEW
                        ? "a.user_no,a.mch_account_no"
                        : key;
        return Boolean.TRUE.equals(
                jdbc.queryForObject(
                        """
SELECT EXISTS(SELECT 1 FROM mch_store s
WHERE s.tenant_no=? AND s.deleted=0 AND %s
AND (s.entity_no=? OR EXISTS(SELECT 1 FROM mch_account a
WHERE a.tenant_no=s.tenant_no AND a.entity_no=s.entity_no AND ? IN (%s) AND a.status='ACTIVE' AND a.deleted=0)))
"""
                                .formatted(NON_LEGACY_MODEL, keys),
                        Boolean.class,
                        user.tenantNo(),
                        selectedMerchant,
                        user.userNo()));
    }

    record StoreFacts(StoreSnapshot snapshot, String storeStatus, String entityStatus) {}

    /** Financial authorization retains persisted ownership after store/entity soft deletion. */
    Optional<String> financialOwner(StoreRef store) {
        return jdbc.queryForList(
                "SELECT entity_no FROM mch_store WHERE tenant_no=? AND store_no=?",
                String.class, store.tenantNo(), store.storeNo()).stream().findFirst();
    }

    public Optional<StoreSnapshot> find(StoreRef store) {
        return details(store).map(StoreFacts::snapshot);
    }

    Optional<StoreFacts> details(StoreRef store) {
        return jdbc
                .query(
                        """
SELECT s.store_no,s.entity_no,s.business_type_code,s.commerce_model,s.status,m.status
FROM mch_store s LEFT JOIN mch_entity m ON m.tenant_no=s.tenant_no AND m.entity_no=s.entity_no AND m.deleted=0
WHERE s.tenant_no=? AND s.store_no=? AND s.deleted=0
""",
                        (rs, i) -> readFacts(store.tenantNo(), rs),
                        store.tenantNo(),
                        store.storeNo())
                .stream()
                .findFirst();
    }

    private static StoreFacts readFacts(String tenantNo, ResultSet rs) throws SQLException {
        var actual = new StoreRef(tenantNo, rs.getString(1));
        var model = rs.getString(4);
        var active = "ACTIVE".equals(rs.getString(5))
                && ("ACTIVE".equals(rs.getString(6)) || "PENDING_LICENSE".equals(rs.getString(6)));
        return new StoreFacts(new StoreSnapshot(actual, rs.getString(2), rs.getString(3),
                active ? StoreStatus.ACTIVE : StoreStatus.DISABLED,
                model == null ? CommerceModel.LEGACY : CommerceModel.valueOf(model)),
                rs.getString(5), rs.getString(6));
    }
}
