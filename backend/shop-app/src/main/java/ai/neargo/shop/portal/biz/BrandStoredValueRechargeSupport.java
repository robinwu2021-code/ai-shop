package ai.neargo.shop.portal.biz;
import ai.neargo.shop.auth.*;
import ai.neargo.shop.common.*;
import ai.neargo.shop.paybridge.*;
import ai.neargo.shop.paybridge.dto.StoredValueRechargeProgress;
import ai.neargo.shop.payclient.StoredValueIssuerRechargeAppService;
import ai.neargo.shop.spi.entitlement.*;
import ai.neargo.shop.spi.entitlement.EntitlementDefinition.RedemptionScope;
import ai.neargo.shop.spi.member.CustomerIdentityPort;
import ai.neargo.shop.spi.pay.MarketPort;
import ai.neargo.shop.spi.user.*;
import ai.neargo.shop.spi.storedvalue.*;
import ai.neargo.shop.spi.storedvalue.StoredValueWallets.*;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
@Component
public class BrandStoredValueRechargeSupport {
 @org.springframework.beans.factory.annotation.Autowired private ai.neargo.shop.merchantbridge.StoredValueCollectionContext collection;
 private final StoredValueIssuerRechargeAppService pay;private final EntitlementScopeResolver stores;private final StoredValueExecutionScopePort facts;
 private final CustomerIdentityPort customers;private final MerchantQueryPort merchants;private final MarketPort markets;private final StoreDirectoryPort directory;
 private final StoredValueRequestFingerprint fingerprints;private final boolean enabled;
 public BrandStoredValueRechargeSupport(StoredValueIssuerRechargeAppService pay,EntitlementScopeResolver stores,StoredValueExecutionScopePort facts,CustomerIdentityPort customers,
        MerchantQueryPort merchants,MarketPort markets,StoreDirectoryPort directory,StoredValueRequestFingerprint fingerprints,@Value("${shop.pay.stored-value.brand-execution-enabled:false}")boolean enabled){
  this.pay=pay;this.stores=stores;this.facts=facts;this.customers=customers;this.merchants=merchants;this.markets=markets;this.directory=directory;this.fingerprints=fingerprints;this.enabled=enabled;
 }
 public boolean enabled(){return enabled;}
 public StoredValueIssuers.StoreOptions issuers(String store){var s=scope(store);return collection==null?pay.issuers(s):collection.options(s);}
 public CursorPage<StoredValueIssuerRecharges.Option> options(String store,String customer,String cursor,int size){
  requireEnabled();var ctx=context("brand-recharge-options");var scope=scope(store);
  var person=customers.resolveReference(ctx,new CustomerIdentityPort.ReferenceQuery(scope.entityNo(),store,customer));
  return pay.options(query(scope,new Customer(person.customerNo(),person.userNo())),cursor,size);
 }
 public StoredValueRechargeProgress confirm(String store,BizStoredValueIssuerOperationsController.StoreRechargeRequest c,String key){
  var ctx=context(key);String entity=entity(store);var replay=replay(store,c);
  // Always reconcile the persisted command before resolving expiring customer/option references.
  var original=pay.replay(ctx,entity,replay);
  if(original.found())return StoredValueRechargeProgress.from(pay.resume(ctx,entity,replay));
  requireEnabled();var s=scope(store);
  var person=customers.resolveReference(ctx,new CustomerIdentityPort.ReferenceQuery(entity,store,c.customerRef()));
  var card=directory.cards(List.of(store)).get(store);if(card==null||!card.active()||!entity.equals(card.entityNo()))throw BizException.of(ErrorCode.NOT_FOUND);
  return StoredValueRechargeProgress.from(pay.confirm(ctx,entity,new StoredValueIssuerRecharges.Create(query(s,new Customer(person.customerNo(),person.userNo())),c.optionRef(),replay.customerReferenceHash(),card.storeName())));
 }
 public BizStoredValuePlanRechargeController.ResolveView resolve(String store,BizStoredValueIssuerOperationsController.StoreRechargeRequest c,String key){
  var r=pay.replay(context(key),entity(store),replay(store,c));return new BizStoredValuePlanRechargeController.ResolveView(r.found(),r.found()?StoredValueRechargeProgress.from(r.recharge()):null);
 }
 public StoredValueRechargeProgress cancel(String store,BizStoredValueIssuerOperationsController.StoreRechargeRequest c,String key){
  return StoredValueRechargeProgress.from(pay.cancelCreation(context(key),entity(store),replay(store,c)));
 }
 private StoredValueIssuerRecharges.Replay replay(String store,BizStoredValueIssuerOperationsController.StoreRechargeRequest c){
  if(c.storeNo()!=null&&!store.equals(c.storeNo()))throw BizException.of(ErrorCode.BAD_REQUEST);
  return new StoredValueIssuerRecharges.Replay(store,fingerprints.issuerOption(c.optionRef()),fingerprints.reference(c.customerRef(),store,"BRAND",c.optionRef()));
 }
 private Query query(ExecutionScope s,Customer c){return collection==null?new Query(s,c):collection.query(s,c);}
 private ExecutionScope scope(String store){
  String entity=entity(store);var u=SecurityUtils.requireUser();
  var market=markets.find(merchants.marketOf(entity)).filter(MarketPort.MarketBrief::enabled).orElseThrow(()->BizException.of(ErrorCode.ENTITLEMENT_FEATURE_UNAVAILABLE));
  return facts.current(u.tenantNo(),u.userNo(),entity,store,market.currency());
 }
 private String entity(String store){return stores.resolve(new RedemptionScope("STORES",List.of(store))).entityNo();}
 private static EntitlementContext context(String key){ContractChecks.id(key);var u=SecurityUtils.requireUser();return new EntitlementContext(u.tenantNo(),u.userNo(),key,"0".repeat(64));}
 private void requireEnabled(){if(!enabled)throw BizException.of(ErrorCode.ENTITLEMENT_FEATURE_UNAVAILABLE);}
}
