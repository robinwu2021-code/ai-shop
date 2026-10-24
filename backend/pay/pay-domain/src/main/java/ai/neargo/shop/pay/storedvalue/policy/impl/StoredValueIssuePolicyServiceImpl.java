package ai.neargo.shop.pay.storedvalue.policy.impl;

import ai.neargo.shop.common.BizException;
import static ai.neargo.shop.common.ErrorCode.CONFLICT;
import static ai.neargo.shop.common.ErrorCode.ENTITLEMENT_FEATURE_UNAVAILABLE;
import static ai.neargo.shop.common.ErrorCode.NOT_FOUND;
import ai.neargo.shop.pay.storedvalue.issuer.IssuerRepository;
import ai.neargo.shop.pay.storedvalue.policy.StoredValueIssuePolicyEvaluator;
import ai.neargo.shop.pay.storedvalue.policy.StoredValueIssuePolicyService;
import ai.neargo.shop.pay.storedvalue.policy.StoredValueReleaseService;
import ai.neargo.shop.spi.entitlement.ContractChecks;
import ai.neargo.shop.spi.storedvalue.StoredValuePolicies.CatalogScope;
import ai.neargo.shop.spi.storedvalue.StoredValuePolicies.EffectivePolicy;
import ai.neargo.shop.spi.storedvalue.StoredValuePolicies.PlanBinding;
import ai.neargo.shop.spi.storedvalue.StoredValuePolicies.PolicyLocation;
import ai.neargo.shop.spi.storedvalue.StoredValuePolicies.PolicyVersion;
import ai.neargo.shop.spi.storedvalue.StoredValuePolicies.PolicyView;
import ai.neargo.shop.spi.storedvalue.StoredValuePolicies.Rules;
import ai.neargo.shop.spi.storedvalue.StoredValuePolicies.StoreScope;
import ai.neargo.shop.spi.storedvalue.StoredValuePolicies.Target;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import static ai.neargo.shop.pay.storedvalue.issuer.IssuerRepository.number;
import static ai.neargo.shop.pay.storedvalue.issuer.IssuerRepository.text;

/** Pay-local reads. Core must derive the management/store/brand scope before invoking this service. */
@Service("storedValueIssuePolicyService")
public class StoredValueIssuePolicyServiceImpl implements StoredValueIssuePolicyService {
    private final IssuerRepository db;
    private final StoredValueIssuePolicyEvaluator evaluator;
    public StoredValueIssuePolicyServiceImpl(IssuerRepository db, StoredValueIssuePolicyEvaluator evaluator) {
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

    public PolicyLocation locate(String tenant, String policyNo) {
        ContractChecks.id(tenant); ContractChecks.id(policyNo);
        var row=db.one("SELECT management_no,target_type,target_no,currency FROM sv_issue_policy WHERE tenant_no=? AND policy_no=? AND deleted=0",tenant,policyNo);
        if(row==null)throw BizException.of(NOT_FOUND);
        return new PolicyLocation(text(row,"management_no"),text(row,"target_type"),text(row,"target_no"),text(row,"currency"));
    }
    public PlanBinding planBinding(String tenant,String entity,String store,String version){
        for(String value:List.of(tenant,entity,store,version))ContractChecks.id(value);
        var row=db.one("SELECT r.* FROM sv_release r JOIN sv_release_tier t ON CAST(t.tenant_no AS BINARY(256))=CAST(r.tenant_no AS BINARY(256)) AND CAST(t.release_no AS BINARY(256))=CAST(r.release_no AS BINARY(256)) JOIN sv_recharge_plan_version v ON CAST(v.tenant_no AS BINARY(256))=CAST(t.tenant_no AS BINARY(256)) AND CAST(v.version_no AS BINARY(256))=CAST(t.projected_plan_version_no AS BINARY(256)) JOIN sv_recharge_plan p ON CAST(p.tenant_no AS BINARY(256))=CAST(v.tenant_no AS BINARY(256)) AND CAST(p.plan_no AS BINARY(256))=CAST(v.plan_no AS BINARY(256)) WHERE r.tenant_no=? AND p.entity_no=? AND p.store_no=? AND v.version_no=? AND r.source_kind='V2_ACTIVITY' AND r.state='ACTIVE' AND p.status='ACTIVE' AND CAST(p.current_version_no AS BINARY(256))=CAST(v.version_no AS BINARY(256)) AND p.deleted=0 AND v.deleted=0 AND r.deleted=0 AND t.deleted=0",tenant,entity,store,version);
        if(row==null)throw BizException.of(ENTITLEMENT_FEATURE_UNAVAILABLE);
        var evidence=db.decode(text(row,"policy_versions"),StoredValueReleaseService.Evidence.class);
        return new PlanBinding(evidence.scope(),evidence.entityNo(),text(row,"activity_version_no"),version);
    }
    public List<PolicyView> list(CatalogScope scope) {
        ContractChecks.present(scope); var result=new ArrayList<PolicyView>();
        for(var target:scope.targets().stream().distinct().toList()) {
            var row=db.one("SELECT p.target_type,p.target_no,p.currency,v.* FROM sv_issue_policy p JOIN sv_issue_policy_version v ON CAST(v.tenant_no AS BINARY(256))=CAST(p.tenant_no AS BINARY(256)) AND CAST(v.policy_no AS BINARY(256))=CAST(p.policy_no AS BINARY(256)) AND CAST(v.policy_version_no AS BINARY(256))=CAST(p.current_version_no AS BINARY(256)) WHERE p.tenant_no=? AND p.management_no=? AND p.target_type=? AND p.target_no=? AND p.currency=? AND p.deleted=0 AND v.deleted=0",scope.tenantNo(),scope.managementNo(),target.type(),target.no(),scope.currency());
            if(row!=null) result.add(new PolicyView(text(row,"policy_no"),text(row,"policy_version_no"),number(row,"version_index")-1,text(row,"target_type"),text(row,"target_no"),text(row,"currency"),versionRules(row),List.of(db.decode(text(row,"parent_version_refs"),String[].class)),text(row,"content_hash")));
        }
        return List.copyOf(result);
    }
    private Rules versionRules(Map<String,Object> row) {
        return new Rules(number(row,"allow_issue")==1,number(row,"require_approval")==1,Math.toIntExact(number(row,"max_bonus_bps")),number(row,"max_paid_minor"),number(row,"max_bonus_minor"),Math.toIntExact(number(row,"max_active_days")),number(row,"max_outstanding_minor"));
    }
    public void requireStoreAccess(StoreScope scope,String entity,String issuer){
        if(!db.payTransactionActive()||scope.storeRevision()<0)throw BizException.of(ENTITLEMENT_FEATURE_UNAVAILABLE);
        var row=db.one("SELECT status,can_issue,source_revision,operating_entity_no FROM sv_issuer_store_access WHERE tenant_no=? AND issuer_no=? AND store_no=? AND deleted=0 FOR UPDATE",scope.tenantNo(),issuer,scope.storeNo());
        if(row==null||!"ACTIVE".equals(text(row,"status"))||number(row,"can_issue")!=1||number(row,"source_revision")!=scope.storeRevision()||!entity.equals(text(row,"operating_entity_no")))throw BizException.of(ENTITLEMENT_FEATURE_UNAVAILABLE);
    }
    private EffectivePolicy read(StoreScope scope, boolean lock) {
        ContractChecks.present(scope);
        if(lock){
            var versions=new ArrayList<PolicyVersion>();
            var targets=new ArrayList<Target>();targets.add(new Target("SPACE",scope.managementNo()));
            if(scope.brandNo()!=null)targets.add(new Target("BRAND_STORES",scope.brandNo()));targets.add(new Target("STORE",scope.storeNo()));
            for(var target:targets){
                var head=db.one("SELECT * FROM sv_issue_policy WHERE tenant_no=? AND management_no=? AND target_type=? AND target_no=? AND currency=? AND status='ACTIVE' AND deleted=0 FOR UPDATE",scope.tenantNo(),scope.managementNo(),target.type(),target.no(),scope.currency());
                if(head==null)continue;
                var immutable=db.one("SELECT * FROM sv_issue_policy_version WHERE tenant_no=? AND policy_no=? AND policy_version_no=? AND deleted=0 FOR UPDATE",scope.tenantNo(),text(head,"policy_no"),text(head,"current_version_no"));
                if(immutable==null)throw BizException.of(CONFLICT);
                var row=new LinkedHashMap<String,Object>(head);row.putAll(immutable);versions.add(version(row));
            }
            return evaluator.evaluate(scope,versions);
        }
        var rows = db.rows("SELECT p.policy_no,p.tenant_no,p.management_no,p.target_type,p.target_no,p.currency,"
                + "v.policy_version_no,v.allow_issue,v.require_approval,v.max_bonus_bps,v.max_paid_minor,"
                + "v.max_bonus_minor,v.max_active_days,v.max_outstanding_minor FROM sv_issue_policy p "
                + "JOIN sv_issue_policy_version v ON CAST(v.tenant_no AS BINARY(256))=CAST(p.tenant_no AS BINARY(256)) AND CAST(v.policy_no AS BINARY(256))=CAST(p.policy_no AS BINARY(256)) "
                + "AND CAST(v.policy_version_no AS BINARY(256))=CAST(p.current_version_no AS BINARY(256)) WHERE p.tenant_no=? AND p.management_no=? "
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
