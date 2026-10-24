package ai.neargo.shop.pay.storedvalue.issuer;

import static ai.neargo.shop.pay.storedvalue.issuer.IssuerRepository.*;
import ai.neargo.shop.common.*;
import ai.neargo.shop.pay.entitlement.service.EntitlementRecords;
import ai.neargo.shop.pay.storedvalue.policy.*;
import ai.neargo.shop.pay.storedvalue.service.StoredValueCursorCodec;
import ai.neargo.shop.spi.entitlement.ContractChecks;
import ai.neargo.shop.spi.storedvalue.StoredValueIssuerRecharges.Option;
import ai.neargo.shop.spi.storedvalue.StoredValuePolicies.BrandScope;
import ai.neargo.shop.spi.storedvalue.StoredValueWallets.Query;
import java.sql.Timestamp;
import java.time.*;
import java.util.*;
import org.springframework.stereotype.Service;

/** Signed offers are read-only. The writer revalidates the immutable tier, access and current rules under Pay locks. */
@Service
public class StoredValueBrandRechargeOptions {
    public record Selected(String releaseNo,String tierNo) { }
    private final IssuerRepository db;private final StoredValueCursorCodec refs;private final StoredValueBrandRuntimeReadiness readiness;private final StoredValueIssuePolicyService policies;private final StoredValueIssuePolicyEvaluator evaluator;
    public StoredValueBrandRechargeOptions(IssuerRepository db,StoredValueCursorCodec refs,StoredValueBrandRuntimeReadiness readiness,StoredValueIssuePolicyService policies,StoredValueIssuePolicyEvaluator evaluator){this.db=db;this.refs=refs;this.readiness=readiness;this.policies=policies;this.evaluator=evaluator;}
    public CursorPage<Option> options(Query q,String cursor,int size){
        ContractChecks.require(size>0&&size<=50);readiness.require();if(q.scope().brandNo()==null)return new CursorPage<>(List.of(),null);
        var s=q.scope();var after=refs.decode(cursor,"SV_BRAND_OPTIONS",s.tenantNo(),s.actorNo(),filters(q));var args=new ArrayList<Object>(Arrays.asList(s.tenantNo(),s.brandNo(),s.currency(),s.storeNo(),s.entityNo(),s.brandBindingNo(),s.brandBindingRevision()));String keyset="";
        if(after!=null){keyset=" AND (r.created_at<? OR (r.created_at=? AND t.tier_no<?))";var at=Timestamp.from(Instant.ofEpochMilli(after.occurredAtMillis()));args.add(at);args.add(at);args.add(after.sortKey());}args.add(size+1);
        var rows=db.rows("SELECT r.*,t.tier_no,t.paid_amount_minor,t.bonus_amount_minor FROM sv_release r JOIN sv_release_tier t ON CAST(t.tenant_no AS BINARY(256))=CAST(r.tenant_no AS BINARY(256)) AND CAST(t.release_no AS BINARY(256))=CAST(r.release_no AS BINARY(256)) JOIN sv_issuer i ON CAST(i.tenant_no AS BINARY(256))=CAST(r.tenant_no AS BINARY(256)) AND CAST(i.issuer_no AS BINARY(256))=CAST(r.issuer_no AS BINARY(256)) AND CAST(i.current_issuer_version_no AS BINARY(256))=CAST(r.issuer_version_no AS BINARY(256)) JOIN sv_issuer_store_access a ON CAST(a.tenant_no AS BINARY(256))=CAST(i.tenant_no AS BINARY(256)) AND CAST(a.issuer_no AS BINARY(256))=CAST(i.issuer_no AS BINARY(256)) WHERE r.tenant_no=? AND i.scope_type='BRAND' AND i.scope_no=? AND r.currency=? AND a.store_no=? AND a.operating_entity_no=? AND a.source_binding_no=? AND a.source_revision=? AND i.status='ACTIVE' AND i.write_mode='ISSUER' AND r.source_kind='V2_BRAND_ACTIVITY' AND r.state='ACTIVE' AND r.activity_version_no IS NOT NULL AND r.starts_at<=CURRENT_TIMESTAMP AND (r.ends_at IS NULL OR r.ends_at>CURRENT_TIMESTAMP) AND a.status='ACTIVE' AND a.can_issue=1 AND a.effective_from<=CURRENT_TIMESTAMP AND (a.effective_to IS NULL OR a.effective_to>CURRENT_TIMESTAMP) AND r.deleted=0 AND t.deleted=0 AND i.deleted=0 AND a.deleted=0"+keyset+" ORDER BY r.created_at DESC,t.tier_no DESC LIMIT ?",args.toArray());
        boolean more=rows.size()>size;var chosen=rows.subList(0,Math.min(size,rows.size()));var result=new ArrayList<Option>();
        for(var row:chosen){var evidence=db.decode(text(row,"policy_versions"),StoredValueReleaseService.Evidence.class);var scope=new BrandScope(s.tenantNo(),s.managementNo(),s.brandNo(),text(row,"issuer_no"),s.currency(),s.brandRevision());var policy=policies.evaluate(scope);var original=evidence.original();int days=Math.toIntExact(java.time.temporal.ChronoUnit.DAYS.between(original.startsAt().atZone(ZoneId.of(original.zone())).toLocalDate(),original.endsAt().minusNanos(1).atZone(ZoneId.of(original.zone())).toLocalDate())+1);var check=evaluator.check(policy,new ai.neargo.shop.spi.storedvalue.StoredValuePolicies.IssueRequest(number(row,"paid_amount_minor"),number(row,"bonus_amount_minor"),days,0,0));
            if(!check.allowed()||check.approvalRequired()&&(!evidence.approvalRequired()||!policy.hash().equals(evidence.policyHash())))continue;
            String ref=refs.encode("SV_BRAND_OPTION",s.tenantNo(),s.actorNo(),filters(q),Instant.now().toEpochMilli(),text(row,"release_no")+":"+text(row,"tier_no"));result.add(new Option(ref,original.name(),s.currency(),number(row,"paid_amount_minor"),number(row,"bonus_amount_minor"),Math.addExact(number(row,"paid_amount_minor"),number(row,"bonus_amount_minor"))));
        }
        String next=null;if(more){var last=chosen.getLast();next=refs.encode("SV_BRAND_OPTIONS",s.tenantNo(),s.actorNo(),filters(q),instant(last.get("created_at")).toEpochMilli(),text(last,"tier_no"));}return new CursorPage<>(result,next);
    }
    public Selected resolve(Query q,String reference){var s=q.scope();var decoded=refs.decode(reference,"SV_BRAND_OPTION",s.tenantNo(),s.actorNo(),filters(q));if(decoded==null)throw BizException.of(ErrorCode.BAD_REQUEST);var parts=decoded.sortKey().split(":",-1);if(parts.length!=2)throw BizException.of(ErrorCode.BAD_REQUEST);ContractChecks.id(parts[0]);ContractChecks.id(parts[1]);return new Selected(parts[0],parts[1]);}
    private String filters(Query q){var s=q.scope();return EntitlementRecords.digest(db.encode(Arrays.asList(s.tenantNo(),s.managementNo(),s.entityNo(),s.storeNo(),s.storeRevision(),s.brandNo(),s.brandBindingNo(),s.brandBindingRevision(),s.currency(),q.customer().customerNo(),q.customer().userNo())));}
    private static Instant instant(Object value){if(value instanceof Timestamp t)return t.toInstant();if(value instanceof LocalDateTime t)return t.toInstant(ZoneOffset.UTC);if(value instanceof Instant t)return t;throw new IllegalStateException("Missing Pay timestamp");}
}
