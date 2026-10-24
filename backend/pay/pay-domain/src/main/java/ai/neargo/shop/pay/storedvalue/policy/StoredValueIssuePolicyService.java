package ai.neargo.shop.pay.storedvalue.policy;

import static ai.neargo.shop.pay.storedvalue.issuer.IssuerRepository.*;
import ai.neargo.shop.pay.storedvalue.issuer.IssuerRepository;
import ai.neargo.shop.spi.entitlement.ContractChecks;
import ai.neargo.shop.spi.storedvalue.StoredValuePolicies.*;
import java.util.Map;
import org.springframework.stereotype.Service;

/** Pay-local reads. Core must derive the management/store/brand scope before invoking this service. */
@Service
public class StoredValueIssuePolicyService {
    private final IssuerRepository db;
    private final StoredValueIssuePolicyEvaluator evaluator;
    public StoredValueIssuePolicyService(IssuerRepository db, StoredValueIssuePolicyEvaluator evaluator) {
        this.db = db; this.evaluator = evaluator;
    }

    /** Preview only, never sufficient by itself to publish a release or authorize issuance. */
    public EffectivePolicy evaluate(StoreScope scope) { return read(scope, false); }

    /** The caller owns the Pay transaction and must follow policy -> issuer/quota lock order. */
    public EffectivePolicy evaluateLocked(StoreScope scope) {
        if (!db.payTransactionActive())
            throw new IllegalStateException("Policy authorization requires a Pay transaction");
        return read(scope, true);
    }

    private EffectivePolicy read(StoreScope scope, boolean lock) {
        ContractChecks.present(scope);
        var rows = db.rows("SELECT p.policy_no,p.tenant_no,p.management_no,p.target_type,p.target_no,p.currency,"
                + "v.policy_version_no,v.allow_issue,v.require_approval,v.max_bonus_bps,v.max_paid_minor,"
                + "v.max_bonus_minor,v.max_active_days,v.max_outstanding_minor FROM sv_issue_policy p "
                + "JOIN sv_issue_policy_version v ON v.tenant_no=p.tenant_no AND v.policy_no=p.policy_no "
                + "AND v.policy_version_no=p.current_version_no WHERE p.tenant_no=? AND p.management_no=? "
                + "AND p.currency=? AND p.status='ACTIVE' AND p.deleted=0 AND v.deleted=0 AND "
                + "((p.target_type='SPACE' AND p.target_no=?) OR (p.target_type='STORE' AND p.target_no=?) "
                + "OR (p.target_type='BRAND_STORES' AND p.target_no=?)) ORDER BY CASE p.target_type WHEN 'SPACE' THEN 0 WHEN 'BRAND_STORES' THEN 1 ELSE 2 END,p.policy_no"
                + (lock ? " FOR UPDATE" : ""), scope.tenantNo(), scope.managementNo(), scope.currency(),
                scope.managementNo(), scope.storeNo(), scope.brandNo());
        return evaluator.evaluate(scope, rows.stream().map(this::version).toList());
    }
    private PolicyVersion version(Map<String,Object> row) {
        var rules = new Rules(number(row,"allow_issue") == 1, number(row,"require_approval") == 1,
                Math.toIntExact(number(row,"max_bonus_bps")), number(row,"max_paid_minor"), number(row,"max_bonus_minor"),
                Math.toIntExact(number(row,"max_active_days")), number(row,"max_outstanding_minor"));
        return new PolicyVersion(text(row,"tenant_no"), text(row,"management_no"), text(row,"policy_no"),
                text(row,"policy_version_no"), text(row,"target_type"), text(row,"target_no"), text(row,"currency"), rules);
    }
}
