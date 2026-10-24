package ai.neargo.shop.portal.biz;

import ai.neargo.shop.auth.*;
import ai.neargo.shop.common.*;
import ai.neargo.shop.paybridge.*;
import ai.neargo.shop.paybridge.dto.StoredValueRechargeProgress;
import ai.neargo.shop.payclient.*;
import ai.neargo.shop.spi.entitlement.*;
import ai.neargo.shop.spi.entitlement.EntitlementDefinition.RedemptionScope;
import ai.neargo.shop.spi.member.CustomerIdentityPort;
import ai.neargo.shop.spi.storedvalue.StoredValueIssuers.*;
import ai.neargo.shop.spi.storedvalue.StoredValuePlans;
import java.time.*;
import java.util.*;
import org.springframework.context.annotation.Profile;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

/** Store execution keeps STORE and BRAND funding separate while reusing the original receipt and ledger. */
@Profile("api")
@RestController
public class BizStoredValueIssuerOperationsController {
 @org.springframework.beans.factory.annotation.Autowired private BrandStoredValueRechargeSupport brand;
 private final StoredValueIssuerAppService issuers;private final EntitlementScopeResolver scopes;private final CustomerIdentityPort customers;
 private final BizStoredValuePlanController plans;private final BizStoredValuePlanRechargeController recharge;private final BizStoredValueMerchantQueryController records;
 public BizStoredValueIssuerOperationsController(StoredValueIssuerAppService issuers,EntitlementScopeResolver scopes,CustomerIdentityPort customers,BizStoredValuePlanController plans,BizStoredValuePlanRechargeController recharge,BizStoredValueMerchantQueryController records){this.issuers=issuers;this.scopes=scopes;this.customers=customers;this.plans=plans;this.recharge=recharge;this.records=records;}
 @GetMapping("/biz/stores/{storeNo}/stored-value/issuers")
 @PreAuthorize("@perm.canBiz('pay:sv:account:read') or @perm.canBiz('pay:sv:recharge:create') or @perm.canBiz('pay:sv:plan:manage') or @perm.canBiz('pay:sv:plan:read')")
 public StoreOptions issuers(@PathVariable String storeNo){
  var s=scopes.resolve(new RedemptionScope("STORES",List.of(storeNo)));
  var actor=BizContext.current().withStore(storeNo);
  if(!actor.can(BizPerms.SV_ACCOUNT_READ)&&!actor.can(BizPerms.SV_RECHARGE_CREATE)&&!actor.can(BizPerms.SV_PLAN_MANAGE)&&!actor.can(BizPerms.SV_PLAN_READ))throw BizException.of(ErrorCode.FORBIDDEN);
  return brand.enabled()?brand.issuers(storeNo):issuers.storeOptions(new StoreQuery(s.tenantNo(),s.actorNo(),s.entityNo(),storeNo));
 }
 public record RechargeOptions(StoreOptions issuerOptions,CursorPage<StoredValuePlans.View> plans,CursorPage<ai.neargo.shop.spi.storedvalue.StoredValueIssuerRecharges.Option> brandOptions){public RechargeOptions(StoreOptions issuers,CursorPage<StoredValuePlans.View> plans){this(issuers,plans,null);}}
 @GetMapping("/biz/stores/{storeNo}/stored-value/recharge-options")
 @PreAuthorize("@perm.canBiz('pay:sv:recharge:create')")
 public RechargeOptions options(@PathVariable String storeNo,@RequestParam String customerRef,@RequestParam(defaultValue="STORE")String scopeType,@RequestParam(required=false)String cursor,@RequestParam(defaultValue="20")int size){
  if("BRAND".equals(scopeType)){require(storeNo,BizPerms.SV_RECHARGE_CREATE);return new RechargeOptions(issuers(storeNo),new CursorPage<>(List.of(),null),brand.options(storeNo,customerRef,cursor,size));}
  storeOnly(scopeType);var s=scopes.resolve(new RedemptionScope("STORES",List.of(storeNo)));require(storeNo,BizPerms.SV_RECHARGE_CREATE);
  var user=SecurityUtils.requireUser();customers.resolveReference(new EntitlementContext(user.tenantNo(),user.userNo(),"issuer-recharge-options","0".repeat(64)),new CustomerIdentityPort.ReferenceQuery(s.entityNo(),storeNo,customerRef));
  return new RechargeOptions(issuers(storeNo),plans.list(storeNo,"ACTIVE",cursor,size));
 }
 public record StoreRechargeRequest(String customerRef,String planNo,String versionNo,String storeNo,String scopeType,String issuerNo,String optionRef){
  public StoreRechargeRequest(String customerRef,String planNo,String versionNo,String storeNo,String scopeType,String issuerNo){this(customerRef,planNo,versionNo,storeNo,scopeType,issuerNo,null);}
  public StoreRechargeRequest{ContractChecks.id(customerRef);if("BRAND".equals(scopeType)){ContractChecks.require(optionRef!=null&&!optionRef.isBlank()&&optionRef.length()<=2048&&planNo==null&&versionNo==null);}else{storeOnly(scopeType);ContractChecks.id(planNo);ContractChecks.id(versionNo);ContractChecks.require(optionRef==null);}}
  @Override public String toString(){return "StoreRechargeRequest[customerRef=REDACTED]";}
 }
 private BizStoredValuePlanRechargeController.CreateRequest original(String store,StoreRechargeRequest request){
  storeOnly(request.scopeType());if(request.storeNo()!=null&&!store.equals(request.storeNo()))throw BizException.of(ErrorCode.BAD_REQUEST);
  if(request.issuerNo()!=null&&issuers(store).issuers().stream().noneMatch(i->request.issuerNo().equals(i.issuerNo())))throw BizException.of(ErrorCode.NOT_FOUND);
  return new BizStoredValuePlanRechargeController.CreateRequest(request.customerRef(),store,request.planNo(),request.versionNo());
 }
 @PostMapping("/biz/stores/{storeNo}/stored-value/issuer-recharges/confirm-offline")
 @PreAuthorize("@perm.canBiz('pay:sv:recharge:create') and @perm.canBiz('pay:sv:recharge:receive')")
 public StoredValueRechargeProgress confirm(@PathVariable String storeNo,@RequestBody StoreRechargeRequest request,@RequestHeader("Idempotency-Key")String key){require(storeNo,BizPerms.SV_RECHARGE_CREATE);require(storeNo,BizPerms.SV_RECHARGE_RECEIVE);return "BRAND".equals(request.scopeType())?brand.confirm(storeNo,request,key):recharge.confirmOffline(original(storeNo,request),key);}
 @GetMapping("/biz/stores/{storeNo}/stored-value/issuer-recharges/{rechargeNo}")
 @PreAuthorize("@perm.canBiz('pay:sv:recharge:read') or @perm.canBiz('pay:sv:recharge:create')")
 public StoredValueRechargeProgress query(@PathVariable String storeNo,@PathVariable String rechargeNo){var r=recharge.query(rechargeNo);if(!storeNo.equals(r.storeNo()))throw BizException.of(ErrorCode.NOT_FOUND);return r;}
 @PostMapping("/biz/stores/{storeNo}/stored-value/issuer-recharges/resolve")
 @PreAuthorize("@perm.canBiz('pay:sv:recharge:create')")
 public BizStoredValuePlanRechargeController.ResolveView resolve(@PathVariable String storeNo,@RequestBody StoreRechargeRequest request,@RequestHeader("Idempotency-Key")String key){require(storeNo,BizPerms.SV_RECHARGE_CREATE);return "BRAND".equals(request.scopeType())?brand.resolve(storeNo,request,key):recharge.resolve(original(storeNo,request),key);}
 @PostMapping("/biz/stores/{storeNo}/stored-value/issuer-recharges/cancel-create")
 @PreAuthorize("@perm.canBiz('pay:sv:recharge:create')")
 public StoredValueRechargeProgress cancelCreate(@PathVariable String storeNo,@RequestBody StoreRechargeRequest request,@RequestHeader("Idempotency-Key")String key){require(storeNo,BizPerms.SV_RECHARGE_CREATE);return "BRAND".equals(request.scopeType())?brand.cancel(storeNo,request,key):recharge.cancelCreation(original(storeNo,request),key);}
 @PostMapping("/biz/stores/{storeNo}/stored-value/issuer-recharges/{rechargeNo}/cancel")
 @PreAuthorize("@perm.canBiz('pay:sv:recharge:create')")
 public StoredValueRechargeProgress cancel(@PathVariable String storeNo,@PathVariable String rechargeNo,@RequestBody BizStoredValuePlanRechargeController.CancelRequest request,@RequestHeader("Idempotency-Key")String key){query(storeNo,rechargeNo);require(storeNo,BizPerms.SV_RECHARGE_CREATE);return recharge.cancel(rechargeNo,request,key);}
 @GetMapping("/biz/stores/{storeNo}/stored-value/issuer-records")
 @PreAuthorize("@perm.canBiz('pay:sv:account:read')")
 public CursorPage<BizStoredValueMerchantQueryController.RecordView> records(@PathVariable String storeNo,@RequestParam(defaultValue="STORE")String scopeType,@RequestParam(defaultValue="ALL")String type,@RequestParam(required=false)LocalDate startDate,@RequestParam(required=false)LocalDate endDate,@RequestParam(required=false)String accountRef,@RequestParam(required=false)String cursor,@RequestParam(defaultValue="20")int size){storeOnly(scopeType);return records.records(storeNo,type,null,null,startDate,endDate,accountRef,cursor,size);}
 @GetMapping("/biz/stores/{storeNo}/stored-value/issuer-records/{recordNo}")
 @PreAuthorize("@perm.canBiz('pay:sv:account:read')")
 public BizStoredValueMerchantQueryController.RecordView record(@PathVariable String storeNo,@PathVariable String recordNo,@RequestParam(defaultValue="STORE")String scopeType){storeOnly(scopeType);return records.record(recordNo,storeNo);}
 private static void storeOnly(String type){if(type!=null&&!"STORE".equals(type))throw BizException.of(ErrorCode.ENTITLEMENT_FEATURE_UNAVAILABLE);}
 private static void require(String store,String permission){if(!BizContext.current().withStore(store).can(permission))throw BizException.of(ErrorCode.FORBIDDEN);}
}
