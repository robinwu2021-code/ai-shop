package ai.neargo.shop.payclient;
import ai.neargo.shop.pay.entitlement.dto.CatalogScope;
import ai.neargo.shop.pay.migration.dto.LegacyCouponIssueViews.*;
import java.util.List;
public interface LegacyCouponIssueAppService {
    View issue(CatalogScope scope,Command command,String requestKey);
    View get(CatalogScope scope,String issueNo);
    View resume(CatalogScope scope,String issueNo,int limit);
    List<View> list(CatalogScope scope,String sourceSystem,String sourceTemplateNo);
}
