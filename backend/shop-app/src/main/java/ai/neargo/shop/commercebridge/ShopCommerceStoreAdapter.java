package ai.neargo.shop.commercebridge;

import ai.neargo.commerce.api.access.AccessDenied;
import ai.neargo.commerce.api.access.AccessStatus;
import ai.neargo.commerce.api.common.*;
import ai.neargo.commerce.api.port.StoreDirectoryPort;
import ai.neargo.shop.auth.BizContext;
import ai.neargo.shop.auth.LoginUser;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.*;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.Optional;

import javax.sql.DataSource;

/** Onboarding uses its separate authority to read its own uncommitted rows. */
final class ShopCommerceStoreAdapter implements StoreDirectoryPort {
    private final JdbcTemplate jdbc;
    private final TransactionTemplate fresh;

    ShopCommerceStoreAdapter(DataSource ds, PlatformTransactionManager manager) {
        jdbc = new JdbcTemplate(ds);
        fresh = new TransactionTemplate(manager);
        fresh.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        fresh.setReadOnly(true);
    }

    StoreSnapshot current(StoreRef ref) {
        return fresh.execute(tx -> find(ref).filter(s -> ref.equals(s.store())).orElseThrow());
    }

    boolean hasPersistedNewStores() {
        return Boolean.TRUE.equals(
                jdbc.queryForObject(
                        """
SELECT EXISTS(SELECT 1 FROM mch_store WHERE deleted=0 AND commerce_model IS NOT NULL AND commerce_model<>'LEGACY')
""",
                        Boolean.class));
    }

    boolean affects(LoginUser user, String... candidates) {
        return reachesNewModel(user)
                || java.util.Arrays.stream(candidates)
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
                || reachesNewModel(user)) throw new AccessDenied(AccessStatus.FORBIDDEN);
        find(new StoreRef(user.tenantNo(), target))
                .filter(
                        s ->
                                s.model() == CommerceModel.LEGACY
                                        && s.merchantNo().equals(scope.merchantNo()))
                .orElseThrow(() -> new AccessDenied(AccessStatus.FORBIDDEN));
        return scope.withStore(target);
    }

    private boolean reachesNewModel(LoginUser user) {
        String key =
                switch (user.subjectKind()) {
                    case USR -> "a.user_no";
                    case MCH -> "a.mch_account_no";
                    default -> throw new IllegalArgumentException("Merchant account kind required");
                };
        return Boolean.TRUE.equals(
                jdbc.queryForObject(
                        """
SELECT EXISTS(SELECT 1 FROM mch_account a JOIN mch_store s
ON s.tenant_no=a.tenant_no AND s.entity_no=a.entity_no
WHERE a.tenant_no=? AND %s=? AND a.status='ACTIVE' AND a.deleted=0 AND s.deleted=0
AND s.commerce_model IS NOT NULL AND s.commerce_model<>'LEGACY'
AND (a.is_owner=1 OR EXISTS(SELECT 1 FROM mch_store_role r WHERE r.tenant_no=a.tenant_no
AND r.mch_account_no=a.mch_account_no AND r.store_no=s.store_no AND r.deleted=0)))
"""
                                .formatted(key),
                        Boolean.class,
                        user.tenantNo(),
                        user.userNo()));
    }

    public Optional<StoreSnapshot> find(StoreRef store) {
        return jdbc
                .query(
                        """
SELECT s.store_no,s.entity_no,s.business_type_code,s.commerce_model,s.status,m.status
FROM mch_store s LEFT JOIN mch_entity m ON m.tenant_no=s.tenant_no AND m.entity_no=s.entity_no AND m.deleted=0
WHERE s.tenant_no=? AND s.store_no=? AND s.deleted=0
""",
                        (rs, i) -> {
                            var actual = new StoreRef(store.tenantNo(), rs.getString(1));
                            var model = rs.getString(4);
                            var active =
                                    "ACTIVE".equals(rs.getString(5))
                                            && ("ACTIVE".equals(rs.getString(6))
                                                    || "PENDING_LICENSE".equals(rs.getString(6)));
                            return new StoreSnapshot(
                                    actual,
                                    rs.getString(2),
                                    rs.getString(3),
                                    active ? StoreStatus.ACTIVE : StoreStatus.DISABLED,
                                    model == null
                                            ? CommerceModel.LEGACY
                                            : CommerceModel.valueOf(model));
                        },
                        store.tenantNo(),
                        store.storeNo())
                .stream()
                .findFirst();
    }
}
