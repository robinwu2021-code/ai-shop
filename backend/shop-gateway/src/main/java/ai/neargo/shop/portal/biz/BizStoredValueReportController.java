package ai.neargo.shop.portal.biz;
import ai.neargo.shop.common.*;
import ai.neargo.shop.merchantbridge.StoredValueFinanceContext;
import ai.neargo.shop.payclient.StoredValueFinanceAppService;
import ai.neargo.shop.spi.storedvalue.*;
import java.time.*;
import org.springframework.context.annotation.Profile;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
@Profile("api") @RestController @PreAuthorize("@managementAccess.authenticated()")
public class BizStoredValueReportController {
 private final StoredValueFinanceContext context;private final StoredValueFinanceAppService finance;
 public BizStoredValueReportController(StoredValueFinanceContext context,StoredValueFinanceAppService finance){this.context=context;this.finance=finance;}
 private StoredValueReports.Scope scope(String management,StoredValueFinance.Resource r,String currency,String activity){if(!r.currency().equals(currency))throw BizException.of(ErrorCode.BAD_REQUEST);return activity==null?context.report(management,r,null):context.activityReport(management,r,activity,false);}
 @GetMapping("/biz/management/brands/{brandNo}/stored-value/summary")
 public StoredValueReports.Summary brand(@PathVariable String brandNo,@RequestParam String managementNo,@RequestParam String currency,@RequestParam LocalDate startDate,@RequestParam LocalDate endDate,@RequestParam(required=false)String activityNo){var r=context.reportResource(managementNo,"BRAND",brandNo);return finance.summary(new StoredValueFinance.SummaryRequest(scope(managementNo,r,currency,activityNo),context.period(r,startDate,endDate)));}
 @GetMapping("/biz/management/brands/{brandNo}/stored-value/store-breakdown")
 public CursorPage<StoredValueReports.StoreAttribution> stores(@PathVariable String brandNo,@RequestParam String managementNo,@RequestParam String currency,@RequestParam LocalDate startDate,@RequestParam LocalDate endDate,@RequestParam(required=false)String activityNo,@RequestParam(required=false)String cursor,@RequestParam(defaultValue="20")int size){var r=context.reportResource(managementNo,"BRAND",brandNo);return finance.stores(new StoredValueReports.Query(scope(managementNo,r,currency,activityNo),context.period(r,startDate,endDate),cursor,size));}
 @GetMapping("/biz/management/stored-value/store-issuer-summary")
 public CursorPage<StoredValueReports.Summary> localIssuers(@RequestParam String managementNo,@RequestParam String currency,@RequestParam LocalDate startDate,@RequestParam LocalDate endDate,@RequestParam(required=false)String cursor,@RequestParam(defaultValue="20")int size){return finance.localIssuers(context.localBatch(managementNo,currency,startDate,endDate,cursor,size));}
 @GetMapping("/biz/stores/{storeNo}/stored-value/store-summary")
 public StoredValueReports.Summary store(@PathVariable String storeNo,@RequestParam String managementNo,@RequestParam String currency,@RequestParam LocalDate startDate,@RequestParam LocalDate endDate,@RequestParam(required=false)String activityNo){var r=context.reportResource(managementNo,"STORE",storeNo);return finance.summary(new StoredValueFinance.SummaryRequest(scope(managementNo,r,currency,activityNo),context.period(r,startDate,endDate)));}
 @GetMapping("/biz/management/stored-value/activities/{activityNo}/summary")
 public CursorPage<StoredValueReports.ActivityAttribution> activity(@PathVariable String activityNo,@RequestParam String managementNo,@RequestParam String issuerNo,@RequestParam String currency,@RequestParam LocalDate startDate,@RequestParam LocalDate endDate,@RequestParam(required=false)String cursor,@RequestParam(defaultValue="20")int size){var r=context.activityResource(managementNo,issuerNo);if(!currency.equals(r.currency()))throw BizException.of(ErrorCode.BAD_REQUEST);return finance.activities(new StoredValueReports.Query(context.activityReport(managementNo,r,activityNo,true),context.period(r,startDate,endDate),cursor,size));}
}
