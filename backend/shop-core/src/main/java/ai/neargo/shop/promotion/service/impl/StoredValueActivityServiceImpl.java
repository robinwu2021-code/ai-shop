package ai.neargo.shop.promotion.service.impl;

import static ai.neargo.shop.promotion.service.StoredValueActivityModels.ApprovalView;
import static ai.neargo.shop.promotion.service.StoredValueActivityModels.Decision;
import static ai.neargo.shop.promotion.service.StoredValueActivityModels.Scope;
import static ai.neargo.shop.promotion.service.StoredValueActivityModels.Submission;
import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.common.PageData;
import ai.neargo.shop.promotion.dto.UnifiedActivityDefinition;
import ai.neargo.shop.promotion.dto.UnifiedActivityViews.ActivityView;
import ai.neargo.shop.promotion.dto.UnifiedActivityViews.SaveDraft;
import ai.neargo.shop.promotion.service.ActivityVersionService;
import ai.neargo.shop.spi.storedvalue.StoredValueActivityFinancePort;
import ai.neargo.shop.promotion.service.StoredValueActivityJournal;
import ai.neargo.shop.promotion.service.StoredValueActivityModels;
import ai.neargo.shop.promotion.service.StoredValueActivityService;
import ai.neargo.shop.spi.entitlement.ContractChecks;
import ai.neargo.shop.spi.storedvalue.StoredValuePolicies.BrandScope;
import ai.neargo.shop.spi.storedvalue.StoredValueReleases.Activate;
import ai.neargo.shop.spi.storedvalue.StoredValueReleases.Check;
import ai.neargo.shop.spi.storedvalue.StoredValueReleases.Prepare;
import ai.neargo.shop.spi.storedvalue.StoredValueReleases.Stop;
import ai.neargo.shop.spi.storedvalue.StoredValueReleases.Tier;
import java.util.List;
import java.util.Set;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Durable saga: all Pay calls occur outside a Core transaction. Original keys survive lost acknowledgements. */
@Service("storedValueActivityService")
@Transactional(propagation=Propagation.NOT_SUPPORTED)
public class StoredValueActivityServiceImpl implements StoredValueActivityService {
 private final ActivityVersionService activities;private final StoredValueActivityJournal journal;private final StoredValueActivityFinancePort pay;
 public StoredValueActivityServiceImpl(ActivityVersionService activities,StoredValueActivityJournal journal,StoredValueActivityFinancePort pay){this.activities=activities;this.journal=journal;this.pay=pay;}
 public ActivityView save(Scope scope,SaveDraft command,String key){validate(scope,command.definition());supportedProgram(command.definition());var saved=activities.saveDraft(scope.actor(),command,key);journal.invalidateOlder(scope,saved.activityNo());return saved;}

 public Detail detail(Scope scope,String activity){var value=get(scope,activity);return new Detail(value,journal.latest(scope,activity));}
 public ActivityView get(Scope scope,String activity){var view=activities.get(scope.actor(),activity);validate(scope,view.definition());return view;}
 public ai.neargo.shop.common.PageData<ActivityView> list(Scope scope,String status,long page,long size){return activities.list(scope.actor(),"RECHARGE",status,page,size);}
 public Check check(Scope scope,String activity,long revision){var draft=get(scope,activity);requireRevision(draft,revision);supportedProgram(draft.definition());return pay.check(command(scope,draft,"SV_CHECK"));}
 public Submission submit(Scope scope,String activity,long revision,String key){
  var draft=get(scope,activity);var prior=journal.find(scope,activity,revision);
  if(prior!=null)return resume(scope,prior,draft);
  requireRevision(draft,revision);supportedProgram(draft.definition());var check=pay.check(command(scope,draft,"SV_CHECK"));if(!check.allowed())throw BizException.of(ErrorCode.CONFLICT);
  var saved=journal.register(scope,draft,check.policy(),key);return resume(scope,saved,draft);
 }
 public Submission resume(Scope scope,Submission progress,ActivityView draft){
  if(Set.of("PENDING_APPROVAL","STALE","BLOCKED_POLICY","STOPPED","ACTIVE").contains(progress.state()))return progress;
  if(!progress.activityNo().equals(draft.activityNo())||!progress.draftHash().equals(journal.hash(draft.definition())))throw BizException.of(ErrorCode.CONFLICT);
  if(Set.of("PREPARING","APPROVED").contains(progress.state())){
   requireRevision(draft,Long.parseLong(progress.draftRevisionNo()));
   var original=journal.savedScope(scope.policyScope().tenantNo(),scope.policyScope().managementNo(),progress.linkNo());
   var prepared=pay.prepare(command(scope,draft,progress.linkNo()+"-prepare",original.actorNo()));
   progress=journal.prepared(scope,progress.linkNo(),prepared);
  }
  if(!"PAY_READY".equals(progress.state()))return progress;
  // publish is itself idempotent and returns its original committed version after a lost acknowledgement.
  var version=activities.publish(scope.actor(),progress.activityNo(),Long.parseLong(progress.draftRevisionNo()),progress.linkNo()+"-publish");
  if(!progress.draftHash().equals(journal.hash(version.definition())))throw BizException.of(ErrorCode.CONFLICT);
  var activated=pay.activate(new Activate(scope.policyScope(),scope.entityNo(),scope.actorNo(),progress.releaseNo(),progress.activityNo(),progress.draftRevisionNo(),progress.draftHash(),progress.releaseHash(),version.versionNo(),version.snapshotHash(),journal.approval(scope,progress.linkNo()),progress.linkNo()+"-activate"));
  return journal.activated(scope,progress.linkNo(),version.versionNo(),activated);
 }
 public Submission stop(Scope scope,String activity,long submittedRevision,String key){
  ContractChecks.id(key);get(scope,activity);var prior=journal.find(scope,activity,submittedRevision);if(prior==null)throw BizException.of(ErrorCode.NOT_FOUND);
  var stopping=journal.state(scope,prior.linkNo(),"STOP_REQUESTED");if("STOPPED".equals(stopping.state()))return stopping;
  var stopped=pay.stop(new Stop(scope.policyScope(),scope.entityNo(),scope.actorNo(),prior.releaseNo(),prior.linkNo()+"-stop"));
  if(!"ISSUE_STOPPED".equals(stopped.state()))throw BizException.of(ErrorCode.CONFLICT);
  return journal.state(scope,prior.linkNo(),"STOPPED");
 }
 public ActivityView copy(Scope scope,String activity,String key){var source=get(scope,activity);return save(scope,new SaveDraft(null,null,source.definition()),key);}
 public ApprovalView decide(Scope approver,String approvalNo,String principal,Decision command,String key){
  var approval=journal.approval(approver.policyScope().tenantNo(),approver.policyScope().managementNo(),approvalNo);
  var draft=get(approver,approval.activityNo());var check=pay.check(command(approver,draft,"SV_CHECK"));
  var result=journal.decide(approver,approvalNo,principal,command,key,check.policy(),check.allowed());
  if("APPROVED".equals(result.status())){
   var stored=journal.find(approver,approval.activityNo(),Long.parseLong(approval.draftRevisionNo()));
   resume(approver,stored,get(approver,approval.activityNo()));
  }
  return result;
 }
 private Prepare command(Scope scope,ActivityView draft,String key){return command(scope,draft,key,scope.actorNo());}
 private Prepare command(Scope scope,ActivityView draft,String key,String submittedBy){
  validate(scope,draft.definition());var d=draft.definition();
  return new Prepare(scope.policyScope(),scope.entityNo(),scope.actorNo(),submittedBy,d.name(),draft.activityNo(),Long.toString(draft.revision()),journal.hash(d),d.schedule().startAt(),d.schedule().endAt(),d.schedule().zone(),d.rule().tiers().stream().map(t->new Tier(t.tierNo(),t.payAmountMinor(),t.bonusAmountMinor())).toList(),key);
 }
 private void validate(Scope scope,UnifiedActivityDefinition d){
  ContractChecks.present(d);ContractChecks.require("RECHARGE".equals(d.scene())&&scope.policyScope().currency().equals(d.currency())&&d.storeParticipationScope()!=null&&(scope.policyScope() instanceof BrandScope?"ALL".equals(d.storeParticipationScope().mode())&&d.storeParticipationScope().refs().isEmpty():"STORES".equals(d.storeParticipationScope().mode())&&List.of(scope.policyScope().storeNo()).equals(d.storeParticipationScope().refs()))&&d.schedule()!=null&&d.schedule().startAt()!=null&&d.schedule().endAt()!=null&&d.rule()!=null&&!d.rule().tiers().isEmpty());
  for(var tier:d.rule().tiers())ContractChecks.require(tier.redemptionScope()!=null&&(scope.policyScope() instanceof BrandScope?"ALL".equals(tier.redemptionScope().mode())&&tier.redemptionScope().refs().isEmpty():"STORES".equals(tier.redemptionScope().mode())&&List.of(scope.policyScope().storeNo()).equals(tier.redemptionScope().refs())));
 }
 private void supportedProgram(UnifiedActivityDefinition d){
  // These tier programs never silently ignore marketing audience/budget/quota promises.
  if(d.audienceRef()!=null||d.quotaLimit()!=null||d.budgetLimitMinor()!=null||d.perUserLimit()!=Integer.MAX_VALUE||!d.rule().benefits().isEmpty())throw BizException.of(ErrorCode.ENTITLEMENT_FEATURE_UNAVAILABLE);
 }
 private void requireRevision(ActivityView draft,long revision){if(draft.revision()!=revision||!"DRAFT".equals(draft.status()))throw BizException.of(ErrorCode.CONFLICT);}
}
