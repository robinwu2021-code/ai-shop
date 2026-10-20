package ai.neargo.shop.payclient.impl;
import ai.neargo.shop.pay.entitlement.dto.CatalogScope;
import ai.neargo.shop.pay.migration.dto.LegacyCouponIssueViews.*;
import ai.neargo.shop.pay.migration.service.LegacyCouponIssueService;
import ai.neargo.shop.payclient.LegacyCouponIssueAppService;
import java.util.List;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
@Service
@ConditionalOnProperty(name="shop.pay.deployment",havingValue="embedded",matchIfMissing=true)
public class LegacyCouponIssueAppServiceImpl implements LegacyCouponIssueAppService {
    private final LegacyCouponIssueService pay;
    public LegacyCouponIssueAppServiceImpl(LegacyCouponIssueService pay) {this.pay=pay;}
    public View issue(CatalogScope s,Command c,String key) {return pay.issue(s,c,key);}
    public View get(CatalogScope s,String no) {return pay.get(s,no);}
    public View resume(CatalogScope s,String no,int limit) {return pay.resume(s,no,limit);}
    public List<View> list(CatalogScope s,String system,String no) {return pay.list(s,system,no);}
}
