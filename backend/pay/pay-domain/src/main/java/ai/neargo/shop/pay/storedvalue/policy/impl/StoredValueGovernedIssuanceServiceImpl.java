package ai.neargo.shop.pay.storedvalue.policy.impl;

import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.pay.storedvalue.entity.SvRecharge;
import ai.neargo.shop.pay.storedvalue.entity.SvRechargePlanVersion;
import ai.neargo.shop.pay.storedvalue.issuer.IssuerRepository;
import ai.neargo.shop.pay.storedvalue.issuer.StoredValueIssueQuotaService;
import ai.neargo.shop.pay.storedvalue.policy.StoredValueGovernedIssuanceService;
import ai.neargo.shop.pay.storedvalue.policy.StoredValueIssuePolicyEvaluator;
import ai.neargo.shop.pay.storedvalue.policy.StoredValueIssuePolicyService;
import ai.neargo.shop.pay.storedvalue.policy.StoredValueReleaseService;
import ai.neargo.shop.spi.entitlement.EntitlementContext;
import ai.neargo.shop.spi.storedvalue.RechargeSourcePort.SourceFact;
import ai.neargo.shop.spi.storedvalue.StoredValuePolicies.EffectivePolicy;
import ai.neargo.shop.spi.storedvalue.StoredValuePolicies.IssueRequest;
import ai.neargo.shop.spi.storedvalue.StoredValuePolicies.PolicyVersion;
import ai.neargo.shop.spi.storedvalue.StoredValuePolicies.StoreScope;
import ai.neargo.shop.spi.storedvalue.StoredValuePolicies;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import static ai.neargo.shop.pay.storedvalue.issuer.IssuerRepository.number;
import static ai.neargo.shop.pay.storedvalue.issuer.IssuerRepository.text;
/** Authorizes only newly created commitments. Receipts already received never pass this eligibility gate again. */
@Service("storedValueGovernedIssuanceService")
public class StoredValueGovernedIssuanceServiceImpl implements StoredValueGovernedIssuanceService {
 private final IssuerRepository db;private final StoredValueIssuePolicyService policies;private final StoredValueIssuePolicyEvaluator evaluator;private final StoredValueIssueQuotaService quotas;
 public StoredValueGovernedIssuanceServiceImpl(IssuerRepository db,StoredValueIssuePolicyService policies,StoredValueIssuePolicyEvaluator evaluator,StoredValueIssueQuotaService quotas){this.db=db;this.policies=policies;this.evaluator=evaluator;this.quotas=quotas;}
 public void beforeIssuer(EntitlementContext ctx,String entity,SourceFact source){
  if(!db.payTransactionActive()||source.policyScope()==null||!source.policyScope().tenantNo().equals(ctx.tenantNo())||!source.policyScope().storeNo().equals(source.permit().storeNo())||!source.policyScope().currency().equals(source.permit().currency())||!entity.equals(source.permit().entityNo()))unavailable();
  var effective=policies.evaluateLocked(source.policyScope());
  @SuppressWarnings("unchecked") var marker=(Map<String,EffectivePolicy>)TransactionSynchronizationManager.getResource(this);
  if(marker==null){marker=new HashMap<>();TransactionSynchronizationManager.bindResource(this,marker);TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization(){public void afterCompletion(int status){TransactionSynchronizationManager.unbindResourceIfPossible(StoredValueGovernedIssuanceServiceImpl.this);}});}
  marker.put(source.rechargeNo(),effective);
 }
 public void beforePlanIssuer(EntitlementContext ctx,String entity,String store,String planVersion,StoreScope scope,String rechargeNo){
  if(!db.payTransactionActive()||scope==null||!scope.tenantNo().equals(ctx.tenantNo())||!store.equals(scope.storeNo()))unavailable();
  var policy=policies.evaluateLocked(scope);var binding=policies.planBinding(ctx.tenantNo(),entity,store,planVersion);
  if(!StoredValuePolicies.sameStoreIdentity(binding.scope(),scope)||!entity.equals(binding.entityNo()))unavailable();
  remember(rechargeNo,policy);
 }
 public void freezePlan(EntitlementContext ctx,String entity,SvRechargePlanVersion version,SvRecharge original){
  @SuppressWarnings("unchecked") var marker=(Map<String,EffectivePolicy>)TransactionSynchronizationManager.getResource(this);
  var policy=marker==null?null:marker.get(original.getRechargeNo());if(policy==null||!db.payTransactionActive())unavailable();
  var row=db.one("SELECT r.*,i.write_mode,i.status AS issuer_status,i.scope_no,v.settlement_entity_no FROM sv_release r JOIN sv_release_tier t ON CAST(t.tenant_no AS BINARY(256))=CAST(r.tenant_no AS BINARY(256)) AND CAST(t.release_no AS BINARY(256))=CAST(r.release_no AS BINARY(256)) JOIN sv_issuer i ON CAST(i.tenant_no AS BINARY(256))=CAST(r.tenant_no AS BINARY(256)) AND CAST(i.issuer_no AS BINARY(256))=CAST(r.issuer_no AS BINARY(256)) JOIN sv_issuer_version v ON CAST(v.tenant_no AS BINARY(256))=CAST(r.tenant_no AS BINARY(256)) AND CAST(v.issuer_no AS BINARY(256))=CAST(r.issuer_no AS BINARY(256)) AND CAST(v.issuer_version_no AS BINARY(256))=CAST(r.issuer_version_no AS BINARY(256)) WHERE r.tenant_no=? AND t.projected_plan_version_no=? AND r.source_kind='V2_ACTIVITY' AND r.deleted=0 AND t.deleted=0 AND i.deleted=0 AND v.deleted=0 FOR UPDATE",ctx.tenantNo(),version.getVersionNo());
  if(row==null||!"ACTIVE".equals(text(row,"state"))||!"ISSUER".equals(text(row,"write_mode"))||!"ACTIVE".equals(text(row,"issuer_status"))||!entity.equals(text(row,"settlement_entity_no"))||!original.getStoreNo().equals(text(row,"scope_no"))||!original.getCurrency().equals(text(row,"currency")))unavailable();
  var e=db.decode(text(row,"policy_versions"),StoredValueReleaseService.Evidence.class);if(!StoredValuePolicies.sameStoreIdentity(e.scope(),policy.scope())||!e.entityNo().equals(entity))unavailable();
  policies.requireStoreAccess(policy.scope(),entity,text(row,"issuer_no"));
  Instant now=Instant.now();if(now.isBefore(instant(row.get("starts_at")))||!now.isBefore(instant(row.get("ends_at"))))unavailable();
  if(policy.rules().requireApproval()&&(!e.approvalRequired()||!e.policyHash().equals(policy.hash())))unavailable();
  var tier=db.one("SELECT * FROM sv_release_tier WHERE tenant_no=? AND release_no=? AND projected_plan_version_no=? AND deleted=0",ctx.tenantNo(),text(row,"release_no"),version.getVersionNo());
  if(tier==null||number(tier,"paid_amount_minor")!=original.getPrincipalMinor()||number(tier,"bonus_amount_minor")!=original.getBonusMinor())unavailable();
  var quota=db.one("SELECT * FROM sv_issue_quota WHERE tenant_no=? AND store_no=? AND currency=? AND deleted=0 FOR UPDATE",ctx.tenantNo(),original.getStoreNo(),original.getCurrency());if(quota==null)unavailable();
  var schedule=e.original();int days=Math.toIntExact(Math.addExact(ChronoUnit.DAYS.between(schedule.startsAt().atZone(ZoneId.of(schedule.zone())).toLocalDate(),schedule.endsAt().minusNanos(1).atZone(ZoneId.of(schedule.zone())).toLocalDate()),1));
  if(!evaluator.check(policy,new IssueRequest(original.getPrincipalMinor(),original.getBonusMinor(),days,number(quota,"outstanding_minor"),number(quota,"reserved_minor"))).allowed())unavailable();
  int changed=db.update("UPDATE sv_recharge SET issuer_no=?,issuer_version_no=?,release_no=?,tier_no=?,collection_entity_no=?,settlement_entity_no=? WHERE tenant_no=? AND recharge_no=? AND issuer_no IS NULL",text(row,"issuer_no"),text(row,"issuer_version_no"),text(row,"release_no"),text(tier,"tier_no"),entity,entity,ctx.tenantNo(),original.getRechargeNo());if(changed!=1)throw BizException.of(ErrorCode.CONFLICT);
  quotas.reserve(ctx,original.getRechargeNo());
  db.update("UPDATE sv_issue_quota_reservation SET policy_versions=?,updated_at=?,updated_by=?,version=version+1 WHERE tenant_no=? AND recharge_no=?",db.encode(policy.sources().stream().map(PolicyVersion::versionNo).toList()),Timestamp.from(now),ctx.actorNo(),ctx.tenantNo(),original.getRechargeNo());
 }
 private void remember(String rechargeNo,EffectivePolicy policy){
  @SuppressWarnings("unchecked") var marker=(Map<String,EffectivePolicy>)TransactionSynchronizationManager.getResource(this);
  if(marker==null){marker=new HashMap<>();TransactionSynchronizationManager.bindResource(this,marker);TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization(){public void afterCompletion(int status){TransactionSynchronizationManager.unbindResourceIfPossible(StoredValueGovernedIssuanceServiceImpl.this);}});}
  marker.put(rechargeNo,policy);
 }
 public void freeze(EntitlementContext ctx,String entity,SourceFact source,SvRecharge original){
  @SuppressWarnings("unchecked") var marker=(Map<String,EffectivePolicy>)TransactionSynchronizationManager.getResource(this);
  var policy=marker==null?null:marker.get(source.rechargeNo());if(policy==null||!db.payTransactionActive())unavailable();
  var permit=source.permit();
  var release=db.one("SELECT r.*,i.write_mode,i.status AS issuer_status,i.scope_no,v.settlement_entity_no FROM sv_release r JOIN sv_issuer i ON CAST(i.tenant_no AS BINARY(256))=CAST(r.tenant_no AS BINARY(256)) AND CAST(i.issuer_no AS BINARY(256))=CAST(r.issuer_no AS BINARY(256)) JOIN sv_issuer_version v ON CAST(v.tenant_no AS BINARY(256))=CAST(r.tenant_no AS BINARY(256)) AND CAST(v.issuer_no AS BINARY(256))=CAST(r.issuer_no AS BINARY(256)) AND CAST(v.issuer_version_no AS BINARY(256))=CAST(r.issuer_version_no AS BINARY(256)) WHERE r.tenant_no=? AND r.activity_version_no=? AND r.source_kind='V2_ACTIVITY' AND r.deleted=0 AND i.deleted=0 AND v.deleted=0 FOR UPDATE",ctx.tenantNo(),permit.versionNo());
  if(release==null||!"ACTIVE".equals(text(release,"state"))||!"ISSUER".equals(text(release,"write_mode"))||!"ACTIVE".equals(text(release,"issuer_status"))||!entity.equals(text(release,"settlement_entity_no"))||!permit.storeNo().equals(text(release,"scope_no"))||!permit.activityNo().equals(text(release,"activity_no"))||!permit.snapshotHash().equals(text(release,"activity_version_hash"))||source.customerNo()==null)unavailable();
  var evidence=db.decode(text(release,"policy_versions"),StoredValueReleaseService.Evidence.class);
  if(!StoredValuePolicies.sameStoreIdentity(evidence.scope(),source.policyScope())||!permit.currency().equals(text(release,"currency")))unavailable();
  if(policy.rules().requireApproval()&&(!evidence.approvalRequired()||!evidence.policyHash().equals(policy.hash())))unavailable();
  policies.requireStoreAccess(policy.scope(),entity,text(release,"issuer_no"));
  Instant now=Instant.now();var start=instant(release.get("starts_at"));var end=instant(release.get("ends_at"));if(now.isBefore(start)||!now.isBefore(end))unavailable();
  var tier=db.one("SELECT * FROM sv_release_tier WHERE tenant_no=? AND release_no=? AND legacy_tier_key=? AND deleted=0",ctx.tenantNo(),text(release,"release_no"),permit.tierNo());
  if(tier==null||number(tier,"paid_amount_minor")!=original.getPrincipalMinor()||number(tier,"bonus_amount_minor")!=original.getBonusMinor())unavailable();
  var quota=db.one("SELECT * FROM sv_issue_quota WHERE tenant_no=? AND store_no=? AND currency=? AND deleted=0 FOR UPDATE",ctx.tenantNo(),permit.storeNo(),permit.currency());if(quota==null)unavailable();
  var schedule=evidence.original();int days=Math.toIntExact(Math.addExact(ChronoUnit.DAYS.between(schedule.startsAt().atZone(ZoneId.of(schedule.zone())).toLocalDate(),schedule.endsAt().minusNanos(1).atZone(ZoneId.of(schedule.zone())).toLocalDate()),1));
  if(!evaluator.check(policy,new IssueRequest(original.getPrincipalMinor(),original.getBonusMinor(),days,number(quota,"outstanding_minor"),number(quota,"reserved_minor"))).allowed())unavailable();
  int changed=db.update("UPDATE sv_recharge SET customer_no=?,issuer_no=?,issuer_version_no=?,release_no=?,tier_no=?,collection_entity_no=?,settlement_entity_no=? WHERE tenant_no=? AND recharge_no=? AND issuer_no IS NULL",source.customerNo(),text(release,"issuer_no"),text(release,"issuer_version_no"),text(release,"release_no"),text(tier,"tier_no"),entity,entity,ctx.tenantNo(),original.getRechargeNo());if(changed!=1)throw BizException.of(ErrorCode.CONFLICT);
  quotas.reserve(ctx,original.getRechargeNo());
  db.update("UPDATE sv_issue_quota_reservation SET policy_versions=?,updated_at=?,updated_by=?,version=version+1 WHERE tenant_no=? AND recharge_no=?",db.encode(policy.sources().stream().map(PolicyVersion::versionNo).toList()),Timestamp.from(now),ctx.actorNo(),ctx.tenantNo(),original.getRechargeNo());
 }
 private Instant instant(Object v){if(v instanceof Timestamp t)return t.toInstant();if(v instanceof LocalDateTime t)return t.toInstant(ZoneOffset.UTC);throw BizException.of(ErrorCode.CONFLICT);}
 private static void unavailable(){throw BizException.of(ErrorCode.ENTITLEMENT_FEATURE_UNAVAILABLE);}
}
