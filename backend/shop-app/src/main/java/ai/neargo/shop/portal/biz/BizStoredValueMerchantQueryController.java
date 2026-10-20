package ai.neargo.shop.portal.biz;

import ai.neargo.shop.auth.BizContext;
import ai.neargo.shop.auth.BizPerms;
import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.CursorPage;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.pay.entitlement.dto.CatalogScope;
import ai.neargo.shop.paybridge.EntitlementScopeResolver;
import ai.neargo.shop.payclient.StoredValueMerchantQueryAppService;
import ai.neargo.shop.promotion.service.ActivityCommandDeduplicator;
import ai.neargo.shop.spi.entitlement.EntitlementDefinition.RedemptionScope;
import ai.neargo.shop.spi.storedvalue.StoredValueMerchantViews;
import ai.neargo.shop.spi.user.StoredValueCustomerProfilePort;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import ai.neargo.shop.spi.pay.MarketPort;
import ai.neargo.shop.spi.user.MerchantQueryPort;
import ai.neargo.shop.spi.entitlement.ContractChecks;
import java.util.List;
import org.springframework.context.annotation.Profile;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@Profile("api")
@RestController
public class BizStoredValueMerchantQueryController {
    private final StoredValueMerchantQueryAppService reads;
    private final EntitlementScopeResolver scopes;
    private final StoredValueCustomerProfilePort profiles;
    private final MarketPort markets;
    private final MerchantQueryPort merchants;
    public BizStoredValueMerchantQueryController(StoredValueMerchantQueryAppService reads,
            EntitlementScopeResolver scopes,StoredValueCustomerProfilePort profiles,MarketPort markets,MerchantQueryPort merchants){this.reads=reads;this.scopes=scopes;this.profiles=profiles;this.markets=markets;this.merchants=merchants;}

    @PreAuthorize("@perm.canBiz('"+BizPerms.SV_ACCOUNT_READ+"')")
    @GetMapping("/biz/stored-value/account-directory")
    public CursorPage<AccountView> directory(@RequestParam String storeNo,@RequestParam(required=false) String keyword,
            @RequestParam(required=false) String cursor,@RequestParam(defaultValue="20") int size){
        var s=scope(storeNo);String q=keyword==null?"":keyword.strip();
        ContractChecks.require(size>=1&&size<=100&&q.length()<=100);
        var result=new java.util.ArrayList<AccountView>();
        String next=cursor;
        // Scan only Pay-authorized store accounts; cap work per HTTP request while preserving continuation.
        for(int batch=0;batch<10;batch++){
            var page=reads.directory(s,storeNo,null,ActivityCommandDeduplicator.hash(q),next,size-result.size());
            var ids=page.records().stream().map(StoredValueMerchantViews.Account::identityNo).distinct().toList();
            var matches=q.isEmpty()?ids:profiles.search(s.tenantNo(),s.entityNo(),storeNo,q,ids);
            var people=profiles.profiles(s.tenantNo(),s.entityNo(),storeNo,matches);
            page.records().stream().filter(a->matches.contains(a.identityNo()))
                    .forEach(a->result.add(account(a,people.get(a.identityNo()))));
            next=page.nextCursor();
            if(next==null||result.size()==size)break;
        }
        return new CursorPage<>(result,next);
    }
    @PreAuthorize("@perm.canBiz('"+BizPerms.SV_ACCOUNT_READ+"')")
    @GetMapping("/biz/stored-value/accounts/{accountRef}")
    public AccountView account(@PathVariable String accountRef,@RequestParam String storeNo){
        var s=scope(storeNo);var a=reads.account(s,storeNo,accountRef);
        return account(a,profiles.profiles(s.tenantNo(),s.entityNo(),storeNo,List.of(a.identityNo())).get(a.identityNo()));
    }
    @PreAuthorize("@perm.canBiz('"+BizPerms.SV_ACCOUNT_READ+"')")
    @GetMapping("/biz/stored-value/records")
    public CursorPage<RecordView> records(@RequestParam String storeNo,@RequestParam(defaultValue="ALL") String type,
            @RequestParam(required=false) Instant from,@RequestParam(required=false) Instant to,
            @RequestParam(required=false) LocalDate startDate,@RequestParam(required=false) LocalDate endDate,
            @RequestParam(required=false) String accountRef,@RequestParam(required=false) String cursor,
            @RequestParam(defaultValue="20") int size){
        var s=scope(storeNo);
        if(startDate!=null||endDate!=null){
            ContractChecks.require(from==null&&to==null);
            var market=markets.find(merchants.marketOf(s.entityNo())).filter(MarketPort.MarketBrief::enabled)
                    .orElseThrow(()->BizException.of(ErrorCode.ENTITLEMENT_FEATURE_UNAVAILABLE));
            var zone=ZoneId.of(market.timeZone());
            if(startDate!=null)from=startDate.atStartOfDay(zone).toInstant();
            if(endDate!=null)to=endDate.plusDays(1).atStartOfDay(zone).toInstant();
        }
        var page=reads.records(s,storeNo,type,from,to,accountRef,cursor,size);
        var people=profiles.profiles(s.tenantNo(),s.entityNo(),storeNo,page.records().stream().map(StoredValueMerchantViews.Record::identityNo).distinct().toList());
        return new CursorPage<>(page.records().stream().map(r->record(r,people.get(r.identityNo()))).toList(),page.nextCursor());
    }
    @PreAuthorize("@perm.canBiz('"+BizPerms.SV_ACCOUNT_READ+"')")
    @GetMapping("/biz/stored-value/records/{recordNo}")
    public RecordView record(@PathVariable String recordNo,@RequestParam String storeNo){
        var s=scope(storeNo);var r=reads.record(s,storeNo,recordNo);
        return record(r,profiles.profiles(s.tenantNo(),s.entityNo(),storeNo,List.of(r.identityNo())).get(r.identityNo()));
    }
    private CatalogScope scope(String store){
        var s=scopes.resolve(new RedemptionScope("STORES",List.of(store)));
        if(!BizContext.current().withStore(store).can(BizPerms.SV_ACCOUNT_READ))throw BizException.of(ErrorCode.FORBIDDEN);return s;
    }
    private static AccountView account(StoredValueMerchantViews.Account a,StoredValueCustomerProfilePort.Profile p){
        return new AccountView(a.accountRef(),p==null?"顾客":p.maskedName(),p==null?"":p.maskedPhone(),a.currency(),
                a.balanceMinor(),a.heldMinor(),a.availableMinor(),a.lastOccurredAt(),a.lastRechargeAt());
    }
    private static RecordView record(StoredValueMerchantViews.Record r,StoredValueCustomerProfilePort.Profile p){
        return new RecordView(r.recordNo(),p==null?"顾客":p.maskedName(),p==null?"":p.maskedPhone(),r.type(),r.title(),r.storeNo(),r.storeName(),
                r.currency(),r.signedAmountMinor(),r.balanceAfterMinor(),r.occurredAt(),r.businessNo(),r.planNo(),r.versionNo(),r.paidAmountMinor(),r.bonusAmountMinor());
    }
    public record AccountView(String accountRef,String maskedName,String maskedPhone,String currency,long balanceMinor,
            long heldMinor,long availableMinor,Instant lastOccurredAt,Instant lastRechargeAt){ }
    public record RecordView(String recordNo,String maskedName,String maskedPhone,String type,String title,String storeNo,String storeName,
            String currency,long signedAmountMinor,long balanceAfterMinor,Instant occurredAt,String businessNo,String planNo,String versionNo,
            Long paidAmountMinor,Long bonusAmountMinor){ }
}
