package ai.neargo.shop.paybridge;
import ai.neargo.shop.common.*;
import ai.neargo.shop.pay.entitlement.dto.CatalogScope;
import ai.neargo.shop.pay.migration.dto.LegacyCouponIssueViews.*;
import ai.neargo.shop.payclient.LegacyCouponIssueAppService;
import ai.neargo.shop.spi.entitlement.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronizationManager;
@Service
public class LegacyCouponIssueCoordinator {
    private final LegacyCouponIssueAppService pay;
    private final LegacyCouponIssuedProjectionPort core;
    public LegacyCouponIssueCoordinator(LegacyCouponIssueAppService pay,LegacyCouponIssuedProjectionPort core) {this.pay=pay;this.core=core;}
    public View issue(CatalogScope scope,Command command,String key) {
        outside();var result=pay.issue(scope,command,key);project(scope,result);return result;
    }
    public void recover(CatalogScope scope,String no) {outside();project(scope,pay.get(scope,no));}
    private void project(CatalogScope scope,View result) {
        if(!scope.entityNo().equals(result.entityNo()) || !"COMPLETED".equals(result.status())) throw BizException.of(ErrorCode.ENTITLEMENT_FEATURE_UNAVAILABLE);
        var facts=result.results().stream().filter(i->"ISSUED".equals(i.status())).map(i->new LegacyCouponIssuedProjectionPort.Right(
                i.instanceNo(),i.userNo(),result.entityNo(),result.sourceSystem(),result.sourceTemplateNo(),result.targetTemplateNo(),result.templateVersionNo(),
                result.migrationBatchNo(),result.planHash(),result.issueNo(),i.grantNo(),result.actorNo(),i.grantedAt())).toList();
        core.record(new EntitlementContext(scope.tenantNo(),scope.actorNo(),result.issueNo(),"0".repeat(64)),facts);
    }
    private static void outside() {if(TransactionSynchronizationManager.isActualTransactionActive()) throw BizException.of(ErrorCode.CONFLICT);}
}
