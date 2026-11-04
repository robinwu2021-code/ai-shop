package ai.neargo.commerce.persistence;

import ai.neargo.commerce.api.access.*;
import ai.neargo.commerce.api.common.StoreRef;
import ai.neargo.commerce.core.access.EndAccessService;

import org.springframework.jdbc.core.JdbcTemplate;

import java.util.UUID;

import javax.sql.DataSource;

public final class JdbcEndAccessRepository implements EndAccessService.Repository {
    private final JdbcSubscriptionRepository subscriptions;
    private final JdbcTemplate jdbc;

    public JdbcEndAccessRepository(DataSource ds) {
        subscriptions = new JdbcSubscriptionRepository(ds, ns -> UUID.randomUUID().toString());
        jdbc = new JdbcTemplate(ds);
    }

    public void requireOuter(StoreRef s) {
        subscriptions.requireOuterTransaction(s);
    }

    public EndAccessService.State lockAndRead(
            StoreRef store, String expectedEnd, String code, String client) {
        var account = subscriptions.lock(store);
        subscriptions.verifyRevokedEvidence(store);
        var end =
                jdbc.queryForList(
                        """
SELECT end_code FROM sys_business_end WHERE tenant_no=? AND entitlement_code=? AND client_id=?
AND enabled=1 AND deleted=0 AND scope='STORE' AND release_status='VERIFIED'
AND JSON_CONTAINS(allowed_business_types,JSON_QUOTE(?)) FOR SHARE
""",
                        String.class,
                        store.tenantNo(),
                        code,
                        client,
                        account.businessTypeCode());
        if (end.size() != 1 || !end.getFirst().equals(expectedEnd))
            throw new AccessDenied(AccessStatus.FORBIDDEN);
        var segments = subscriptions.segments(store, code);
        var states =
                jdbc.queryForList(
                        """
SELECT g.state FROM mch_store_end_grant g LEFT JOIN mch_subscription_period p
ON p.tenant_no=g.tenant_no AND p.store_no=g.store_no AND p.period_no=g.period_no
WHERE g.tenant_no=? AND g.store_no=? AND g.entitlement_code=? AND
(g.state IN ('REVOKED','FROZEN') OR (g.state='NORMAL' AND
(p.id IS NULL OR (p.state<>'NORMAL' AND (p.state NOT IN ('CONVERTED','FROZEN','REFUNDED','REVOKED') OR (p.state='REVOKED' AND (p.revoked_by_no IS NULL OR p.revocation_snapshot IS NULL)) OR p.effective_end_at IS NULL OR g.end_at IS NULL OR g.end_at>p.effective_end_at)) OR g.end_code<>? OR p.deleted=1 OR g.deleted=1)))
FOR SHARE
""",
                        String.class,
                        store.tenantNo(),
                        store.storeNo(),
                        code,
                        end.getFirst());
        if (states.contains("NORMAL")) throw new AccessDenied(AccessStatus.CHECK_UNAVAILABLE);
        // Retired source rows are accepted history only, never present/future authority.
        var activeSources =
                new java.util.HashSet<>(
                        jdbc.queryForList(
                                "SELECT period_no FROM mch_subscription_period WHERE tenant_no=?"
                                    + " AND store_no=? AND state='NORMAL' AND deleted=0 FOR SHARE",
                                String.class,
                                store.tenantNo(),
                                store.storeNo()));
        segments =
                segments.stream()
                        .filter(segment -> activeSources.contains(segment.sourceNo()))
                        .toList();
        return new EndAccessService.State(
                account.entityNo(),
                account.businessTypeCode(),
                account.entitlementVersion(),
                segments,
                !states.isEmpty());
    }
}
