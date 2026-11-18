package ai.neargo.shop.portal.biz;

import ai.neargo.shop.common.*;
import ai.neargo.shop.merchant.brand.BrandStoredValueActivityAuthority;
import ai.neargo.shop.merchantbridge.ManagementAccess;
import ai.neargo.shop.paybridge.BrandStoredValueActivityContextService;
import ai.neargo.shop.promotion.dto.UnifiedActivityDefinition;
import ai.neargo.shop.promotion.dto.UnifiedActivityViews.*;
import ai.neargo.shop.promotion.service.*;
import ai.neargo.shop.promotion.service.StoredValueActivityModels.*;
import ai.neargo.shop.spi.entitlement.ContractChecks;
import ai.neargo.shop.spi.storedvalue.StoredValueReleases.Check;
import java.time.*;
import java.util.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
@Profile("api") @RestController @PreAuthorize("@managementAccess.authenticated()")
public class BizBrandStoredValueActivityController {
    private final ManagementAccess access;private final BrandStoredValueActivityAuthority authority;private final BrandStoredValueActivityContextService contexts;private final StoredValueActivityService activities;private final ActivityPresetService presets;
    public BizBrandStoredValueActivityController(ManagementAccess access,BrandStoredValueActivityAuthority authority,BrandStoredValueActivityContextService contexts,StoredValueActivityService activities,ActivityPresetService presets){this.access=access;this.authority=authority;this.contexts=contexts;this.activities=activities;this.presets=presets;}
    public record Draft(String managementNo,UnifiedActivityDefinition definition,Long expectedRevision){public Draft{ContractChecks.id(managementNo);ContractChecks.present(definition);ContractChecks.require(expectedRevision==null||expectedRevision>=0);}}
    public record Command(String managementNo,String currency,long expectedRevision){public Command{ContractChecks.id(managementNo);ContractChecks.currency(currency);ContractChecks.require(expectedRevision>=0);}}
    public record ContextView(String currency,int currencyScale,String timeZone,boolean canManage,boolean canPublish,String issuerName,String issuerStatus) { }
    private BrandStoredValueActivityContextService.Context context(String management,String brand,String currency,String permission){return contexts.resolve(access.actor(),management,brand,currency,permission);}
    @GetMapping("/biz/management/brands/{brandNo}/stored-value/activity-context")
    public ContextView context(@PathVariable String brandNo,@RequestParam String managementNo){var c=context(managementNo,brandNo,null,"sv.brand.activity.read");return new ContextView(c.issuer().currency(),Currency.getInstance(c.issuer().currency()).getDefaultFractionDigits(),c.issuer().marketZone(),authority.can(access.actor(),managementNo,brandNo,"sv.brand.activity.manage"),authority.can(access.actor(),managementNo,brandNo,"sv.brand.activity.publish"),c.issuer().name(),c.issuer().status());}
    @GetMapping("/biz/management/brands/{brandNo}/stored-value/activity-presets")
    public PageData<BizStoredValueActivityController.PresetOption> presets(@PathVariable String brandNo,@RequestParam String managementNo,@RequestParam String currency,@RequestParam(defaultValue="1")long page,@RequestParam(defaultValue="50")long size){var c=context(managementNo,brandNo,currency,"sv.brand.activity.manage");var result=presets.list(c.scope().actor(),"ACTIVITY","RECHARGE",null,null,page,size);return new PageData<>(result.records().stream().map(p->new BizStoredValueActivityController.PresetOption(p.templateCode(),p.templateVersion(),p.name(),p.enabled(),p.available(),p.missingCapabilities())).toList(),result.total(),result.page(),result.size());}
    @GetMapping("/biz/management/brands/{brandNo}/stored-value/activity-schedules")
    public ai.neargo.shop.paybridge.StoredValueActivityScheduleService.Schedule schedule(@PathVariable String brandNo,@RequestParam String managementNo,@RequestParam String currency,@RequestParam String startDate,@RequestParam String endDate){var c=context(managementNo,brandNo,currency,"sv.brand.activity.manage");try{var start=LocalDate.parse(startDate);var end=LocalDate.parse(endDate);if(end.isBefore(start))throw BizException.of(ErrorCode.BAD_REQUEST);var zone=ZoneId.of(c.issuer().marketZone());return new ai.neargo.shop.paybridge.StoredValueActivityScheduleService.Schedule(start.atStartOfDay(zone).toInstant().toString(),end.plusDays(1).atStartOfDay(zone).toInstant().toString(),zone.getId(),currency,Currency.getInstance(currency).getDefaultFractionDigits());}catch(DateTimeException|ArithmeticException invalid){throw BizException.of(ErrorCode.BAD_REQUEST);}}
    @GetMapping("/biz/management/brands/{brandNo}/stored-value/activities")
    public PageData<ActivityView> list(@PathVariable String brandNo,@RequestParam String managementNo,@RequestParam String currency,@RequestParam(required=false)String status,@RequestParam(defaultValue="1")long page,@RequestParam(defaultValue="20")long size){return activities.list(context(managementNo,brandNo,currency,"sv.brand.activity.read").scope(),status,page,size);}
    @PostMapping("/biz/management/brands/{brandNo}/stored-value/activities")
    public ActivityView create(@PathVariable String brandNo,@RequestBody Draft r,@RequestHeader("Idempotency-Key")String key){return activities.save(context(r.managementNo(),brandNo,r.definition().currency(),"sv.brand.activity.manage").scope(),new SaveDraft(null,null,r.definition()),key);}
    @GetMapping("/biz/management/brands/{brandNo}/stored-value/activities/{activityNo}")
    public StoredValueActivityService.Detail get(@PathVariable String brandNo,@PathVariable String activityNo,@RequestParam String managementNo,@RequestParam String currency){return activities.detail(context(managementNo,brandNo,currency,"sv.brand.activity.read").scope(),activityNo);}
    @PutMapping("/biz/management/brands/{brandNo}/stored-value/activities/{activityNo}/draft")
    public ActivityView edit(@PathVariable String brandNo,@PathVariable String activityNo,@RequestBody Draft r,@RequestHeader("Idempotency-Key")String key){return activities.save(context(r.managementNo(),brandNo,r.definition().currency(),"sv.brand.activity.manage").scope(),new SaveDraft(activityNo,r.expectedRevision(),r.definition()),key);}
    @PostMapping("/biz/management/brands/{brandNo}/stored-value/activities/{activityNo}/checks")
    public Check check(@PathVariable String brandNo,@PathVariable String activityNo,@RequestBody Command r){return activities.check(context(r.managementNo(),brandNo,r.currency(),"sv.brand.activity.manage").scope(),activityNo,r.expectedRevision());}
    @PostMapping("/biz/management/brands/{brandNo}/stored-value/activities/{activityNo}/submissions")
    public Submission submit(@PathVariable String brandNo,@PathVariable String activityNo,@RequestBody Command r,@RequestHeader("Idempotency-Key")String key){return activities.submit(context(r.managementNo(),brandNo,r.currency(),"sv.brand.activity.publish").scope(),activityNo,r.expectedRevision(),key);}
    @PostMapping("/biz/management/brands/{brandNo}/stored-value/activities/{activityNo}/stop-issuance")
    public Submission stop(@PathVariable String brandNo,@PathVariable String activityNo,@RequestBody Command r,@RequestHeader("Idempotency-Key")String key){return activities.stop(context(r.managementNo(),brandNo,r.currency(),"sv.brand.activity.manage").scope(),activityNo,r.expectedRevision(),key);}
    @PostMapping("/biz/management/brands/{brandNo}/stored-value/activities/{activityNo}/copies")
    public ActivityView copy(@PathVariable String brandNo,@PathVariable String activityNo,@RequestBody Command r,@RequestHeader("Idempotency-Key")String key){return activities.copy(context(r.managementNo(),brandNo,r.currency(),"sv.brand.activity.manage").scope(),activityNo,key);}
}
