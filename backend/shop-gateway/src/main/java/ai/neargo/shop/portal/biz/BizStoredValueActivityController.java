package ai.neargo.shop.portal.biz;

import ai.neargo.shop.merchant.brand.BrandStoredValueActivityAuthority;
import ai.neargo.shop.paybridge.BrandStoredValueActivityContextService;
import ai.neargo.shop.paybridge.StoredValueActivityScheduleService;
import org.springframework.beans.factory.annotation.Autowired;
import ai.neargo.shop.spi.entitlement.ContractChecks;

import ai.neargo.shop.common.*;
import ai.neargo.shop.merchant.brand.StoredValuePolicyAuthorityService;
import ai.neargo.shop.merchantbridge.ManagementAccess;
import ai.neargo.shop.promotion.dto.UnifiedActivityDefinition;
import ai.neargo.shop.promotion.dto.UnifiedActivityViews.*;
import ai.neargo.shop.promotion.service.*;
import ai.neargo.shop.promotion.service.StoredValueActivityModels.*;
import ai.neargo.shop.spi.storedvalue.StoredValueReleases.Check;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
@Profile("api") @RestController @PreAuthorize("@managementAccess.authenticated()")
@ConditionalOnProperty(name="shop.pay.stored-value.governance-enabled",havingValue="true")
public class BizStoredValueActivityController {
 @Autowired private BrandStoredValueActivityAuthority brandAuthority;
 @Autowired private BrandStoredValueActivityContextService brandContexts;
 private final ActivityPresetService presets;private final StoredValueActivityScheduleService schedules;
 private final ManagementAccess access;private final StoredValuePolicyAuthorityService authority;private final StoredValueActivityService activities;private final StoredValueActivityJournal journal;
 public BizStoredValueActivityController(ManagementAccess access,StoredValuePolicyAuthorityService authority,StoredValueActivityService activities,StoredValueActivityJournal journal,ActivityPresetService presets,StoredValueActivityScheduleService schedules){this.presets=presets;this.schedules=schedules;this.access=access;this.authority=authority;this.activities=activities;this.journal=journal;}
 public record Draft(String managementNo,UnifiedActivityDefinition definition,Long expectedRevision) { public Draft{ContractChecks.id(managementNo);ContractChecks.present(definition);ContractChecks.require(expectedRevision==null||expectedRevision>=0);} }
 public record Command(String managementNo,String currency,long expectedRevision) {public Command{ContractChecks.id(managementNo);ContractChecks.currency(currency);ContractChecks.require(expectedRevision>=0);} }
 public record ApprovalDecision(String managementNo,Decision decision) {public ApprovalDecision{ContractChecks.id(managementNo);ContractChecks.present(decision);} }
 private Scope scope(String management,String store,String currency,String permission){var a=authority.activity(access.actor(),management,store,currency,permission);return new Scope(a.policyScope(),a.entityNo(),a.actorNo(),a.principalNo(),a.authorityRevision());}
 public record ActivityContext(String currency,int currencyScale,String timeZone,boolean canManage,boolean canPublish) { }
 @GetMapping("/biz/stores/{storeNo}/stored-value/activity-context")
 public ActivityContext context(@PathVariable String storeNo,@RequestParam String managementNo,@RequestParam String currency,@RequestParam(defaultValue="READ")String intent){if(!java.util.Set.of("READ","MANAGE").contains(intent))throw BizException.of(ErrorCode.BAD_REQUEST);var market=schedules.context(scope(managementNo,storeNo,currency,"MANAGE".equals(intent)?"sv.store.activity.manage":"sv.store.activity.read"));return new ActivityContext(market.currency(),market.currencyScale(),market.timeZone(),authority.canActivity(access.actor(),managementNo,storeNo,"sv.store.activity.manage"),authority.canActivity(access.actor(),managementNo,storeNo,"sv.store.activity.publish"));}
 public record PresetOption(String templateCode,int templateVersion,String name,boolean enabled,boolean available,java.util.List<String> missingCapabilities) { }
 @GetMapping("/biz/stores/{storeNo}/stored-value/activity-presets")
 public PageData<PresetOption> presets(@PathVariable String storeNo,@RequestParam String managementNo,@RequestParam String currency,@RequestParam(defaultValue="1")long page,@RequestParam(defaultValue="50")long size){var result=presets.list(scope(managementNo,storeNo,currency,"sv.store.activity.manage").actor(),"ACTIVITY","RECHARGE",null,null,page,size);return new PageData<>(result.records().stream().map(p->new PresetOption(p.templateCode(),p.templateVersion(),p.name(),p.enabled(),p.available(),p.missingCapabilities())).toList(),result.total(),result.page(),result.size());}
 @GetMapping("/biz/stores/{storeNo}/stored-value/activity-schedules")
 public StoredValueActivityScheduleService.Schedule schedule(@PathVariable String storeNo,@RequestParam String managementNo,@RequestParam String currency,@RequestParam String startDate,@RequestParam String endDate){return schedules.resolve(scope(managementNo,storeNo,currency,"sv.store.activity.manage"),startDate,endDate);}
 @GetMapping("/biz/stores/{storeNo}/stored-value/activities")
 public PageData<ActivityView> list(@PathVariable String storeNo,@RequestParam String managementNo,@RequestParam String currency,@RequestParam(required=false)String status,@RequestParam(defaultValue="1")long page,@RequestParam(defaultValue="20")long size){return activities.list(scope(managementNo,storeNo,currency,"sv.store.activity.read"),status,page,size);}
 @PostMapping("/biz/stores/{storeNo}/stored-value/activities")
 public ActivityView create(@PathVariable String storeNo,@RequestBody Draft r,@RequestHeader("Idempotency-Key")String key){return activities.save(scope(r.managementNo(),storeNo,r.definition().currency(),"sv.store.activity.manage"),new SaveDraft(null,null,r.definition()),key);}
 @GetMapping("/biz/stores/{storeNo}/stored-value/activities/{activityNo}")
 public StoredValueActivityService.Detail get(@PathVariable String storeNo,@PathVariable String activityNo,@RequestParam String managementNo,@RequestParam String currency){return activities.detail(scope(managementNo,storeNo,currency,"sv.store.activity.read"),activityNo);}
 @PutMapping("/biz/stores/{storeNo}/stored-value/activities/{activityNo}/draft")
 public ActivityView edit(@PathVariable String storeNo,@PathVariable String activityNo,@RequestBody Draft r,@RequestHeader("Idempotency-Key")String key){return activities.save(scope(r.managementNo(),storeNo,r.definition().currency(),"sv.store.activity.manage"),new SaveDraft(activityNo,r.expectedRevision(),r.definition()),key);}
 @PostMapping("/biz/stores/{storeNo}/stored-value/activities/{activityNo}/checks")
 public Check check(@PathVariable String storeNo,@PathVariable String activityNo,@RequestBody Command r){return activities.check(scope(r.managementNo(),storeNo,r.currency(),"sv.store.activity.manage"),activityNo,r.expectedRevision());}
 @PostMapping("/biz/stores/{storeNo}/stored-value/activities/{activityNo}/submissions")
 public Submission submit(@PathVariable String storeNo,@PathVariable String activityNo,@RequestBody Command r,@RequestHeader("Idempotency-Key")String key){return activities.submit(scope(r.managementNo(),storeNo,r.currency(),"sv.store.activity.publish"),activityNo,r.expectedRevision(),key);}
 @PostMapping("/biz/stores/{storeNo}/stored-value/activities/{activityNo}/stop-issuance")
 public Submission stop(@PathVariable String storeNo,@PathVariable String activityNo,@RequestBody Command r,@RequestHeader("Idempotency-Key")String key){return activities.stop(scope(r.managementNo(),storeNo,r.currency(),"sv.store.activity.manage"),activityNo,r.expectedRevision(),key);}
 @PostMapping("/biz/stores/{storeNo}/stored-value/activities/{activityNo}/copies")
 public ActivityView copy(@PathVariable String storeNo,@PathVariable String activityNo,@RequestBody Command r,@RequestHeader("Idempotency-Key")String key){return activities.copy(scope(r.managementNo(),storeNo,r.currency(),"sv.store.activity.manage"),activityNo,key);}
 @GetMapping("/biz/management/stored-value/activity-approvals")
 public CursorPage<ApprovalView> approvals(@RequestParam String managementNo,@RequestParam(required=false)String status,@RequestParam(required=false)String cursor,@RequestParam(defaultValue="20")int size){var actor=access.actor();boolean stores=authority.canApproval(actor,managementNo);var brands=brandAuthority.approvalBrands(actor,managementNo);if(!stores&&brands.isEmpty())throw BizException.of(ErrorCode.FORBIDDEN);return journal.approvalPage(actor.tenantNo(),managementNo,status,cursor,size,stores,brands);}
 @GetMapping("/biz/management/stored-value/activity-approvals/{approvalNo}")
 public ApprovalView approval(@PathVariable String approvalNo,@RequestParam String managementNo){var actor=access.actor();var result=journal.approval(actor.tenantNo(),managementNo,approvalNo);if("BRAND".equals(result.scopeType()))brandAuthority.lookup(actor,managementNo,result.scopeNo(),"sv.brand.activity.approve");else authority.requireApproval(actor,managementNo);return result;}
 @PostMapping("/biz/management/stored-value/activity-approvals/{approvalNo}/decisions")
 public ApprovalView decide(@PathVariable String approvalNo,@RequestBody ApprovalDecision r,@RequestHeader("Idempotency-Key")String key){var actor=access.actor();var approval=journal.approval(actor.tenantNo(),r.managementNo(),approvalNo);var saved=journal.savedScope(actor.tenantNo(),r.managementNo(),approval.linkNo());if("BRAND".equals(approval.scopeType())){var branded=brandContexts.resolve(actor,r.managementNo(),approval.scopeNo(),saved.policyScope().currency(),"sv.brand.activity.approve").scope();return activities.decide(branded,approvalNo,branded.principalNo(),r.decision(),key);}var a=authority.approval(actor,r.managementNo(),approval.storeNo(),saved.policyScope().currency());var scope=new Scope(a.policyScope(),a.entityNo(),a.actorNo(),a.principalNo(),a.authorityRevision());return activities.decide(scope,approvalNo,a.principalNo(),r.decision(),key);}
}
