package ai.neargo.shop.portal.biz;
import ai.neargo.shop.auth.*;
import ai.neargo.shop.common.*;
import ai.neargo.shop.paybridge.EntitlementScopeResolver;
import ai.neargo.shop.payclient.StoredValueWalletAppService;
import ai.neargo.shop.spi.entitlement.*;
import ai.neargo.shop.spi.entitlement.EntitlementDefinition.RedemptionScope;
import ai.neargo.shop.spi.member.CustomerIdentityPort;
import ai.neargo.shop.spi.pay.MarketPort;
import ai.neargo.shop.spi.user.MerchantQueryPort;
import ai.neargo.shop.spi.storedvalue.*;
import ai.neargo.shop.spi.storedvalue.StoredValueWallets.*;
import java.util.List;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
@Profile("api") @RestController
public class BizStoredValueWalletController {
    @org.springframework.beans.factory.annotation.Autowired private ai.neargo.shop.spi.user.StoredValueCustomerProfilePort profiles;
    private final StoredValueWalletAppService wallets;private final CustomerIdentityPort customers;private final EntitlementScopeResolver stores;private final StoredValueExecutionScopePort facts;private final MerchantQueryPort merchants;private final MarketPort markets;
    public BizStoredValueWalletController(StoredValueWalletAppService wallets,CustomerIdentityPort customers,EntitlementScopeResolver stores,StoredValueExecutionScopePort facts,MerchantQueryPort merchants,MarketPort markets){this.wallets=wallets;this.customers=customers;this.stores=stores;this.facts=facts;this.merchants=merchants;this.markets=markets;}
    @PreAuthorize("@perm.canBiz('pay:sv:account:read')")
    @GetMapping("/biz/stores/{storeNo}/stored-value/customer-selections/{customerRef}/accounts")
    public List<AvailableAccount> accounts(@PathVariable String storeNo,@PathVariable String customerRef){
        return wallets.accounts(query(storeNo,customerRef));
    }
    private Query query(String storeNo,String customerRef){
        var user=SecurityUtils.requireUser();var store=stores.resolve(new RedemptionScope("STORES",List.of(storeNo)));
        if(!BizContext.current().withStore(storeNo).can(BizPerms.SV_ACCOUNT_READ))throw BizException.of(ErrorCode.FORBIDDEN);
        var market=markets.find(merchants.marketOf(store.entityNo())).filter(MarketPort.MarketBrief::enabled).orElseThrow(()->BizException.of(ErrorCode.ENTITLEMENT_FEATURE_UNAVAILABLE));
        var context=new EntitlementContext(user.tenantNo(),user.userNo(),"wallet-query","0".repeat(64));
        var customer=customers.resolveReference(context,new CustomerIdentityPort.ReferenceQuery(store.entityNo(),storeNo,customerRef));
        var scope=facts.current(user.tenantNo(),user.userNo(),store.entityNo(),storeNo,market.currency());
        return new Query(scope,new Customer(customer.customerNo(),customer.userNo()));
    }
    @PreAuthorize("@perm.canBiz('pay:sv:account:read')")
    @GetMapping("/biz/stores/{storeNo}/stored-value/customer-selections/{customerRef}/records")
    public CursorPage<BizStoredValueMerchantQueryController.RecordView> records(@PathVariable String storeNo,@PathVariable String customerRef,
            @RequestParam String issuerRef,@RequestParam String accountRef,@RequestParam(defaultValue="ALL")String type,
            @RequestParam(required=false)java.time.LocalDate startDate,@RequestParam(required=false)java.time.LocalDate endDate,
            @RequestParam(required=false)String cursor,@RequestParam(defaultValue="20")int size){
        var query=query(storeNo,customerRef);var s=query.scope();
        var market=markets.find(merchants.marketOf(s.entityNo())).orElseThrow(()->BizException.of(ErrorCode.ENTITLEMENT_FEATURE_UNAVAILABLE));var zone=java.time.ZoneId.of(market.timeZone());
        var page=wallets.records(new ai.neargo.shop.pay.client.PayStoredValueWalletRequests.Records(new ai.neargo.shop.pay.client.PayStoredValueWalletRequests.Select(query,issuerRef,accountRef),type,startDate==null?null:startDate.atStartOfDay(zone).toInstant(),endDate==null?null:endDate.plusDays(1).atStartOfDay(zone).toInstant(),cursor,size));
        var names=profiles.profiles(s.tenantNo(),s.entityNo(),storeNo,page.records().stream().map(ai.neargo.shop.spi.storedvalue.StoredValueMerchantViews.Record::identityNo).distinct().toList());
        return new CursorPage<>(page.records().stream().map(r->BizStoredValueMerchantQueryController.record(r,names.get(r.identityNo()))).toList(),page.nextCursor());
    }
}
