package ai.neargo.shop.payclient.impl;
import ai.neargo.shop.pay.client.*;
import ai.neargo.shop.pay.entitlement.dto.CatalogScope;
import ai.neargo.shop.pay.migration.dto.LegacyCouponIssueViews.*;
import ai.neargo.shop.payclient.*;
import java.util.List;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
@Service
@ConditionalOnProperty(name="shop.pay.deployment",havingValue="standalone")
public class RemoteLegacyCouponIssueAppService implements LegacyCouponIssueAppService {
    private final PayInternalApi pay;
    public RemoteLegacyCouponIssueAppService(PayInternalApi pay) {this.pay=pay;}
    public View issue(CatalogScope s,Command c,String key) {return RemoteEntitlementCalls.call(()->pay.legacyIssue(new PayLegacyIssueRequests.Issue(s,c,key)));}
    public View get(CatalogScope s,String no) {return RemoteEntitlementCalls.call(()->pay.legacyIssueGet(new PayLegacyIssueRequests.Get(s,no)));}
    public View resume(CatalogScope s,String no,int limit) {return RemoteEntitlementCalls.call(()->pay.legacyIssueResume(new PayLegacyIssueRequests.Resume(s,no,limit)));}
    public List<View> list(CatalogScope s,String system,String no) {return RemoteEntitlementCalls.call(()->pay.legacyIssueList(new PayLegacyIssueRequests.History(s,system,no)));}
}
