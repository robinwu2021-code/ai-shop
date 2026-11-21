package ai.neargo.shop.paybridge;
import ai.neargo.shop.auth.Realm;
import ai.neargo.shop.auth.ScopeDim;
import ai.neargo.shop.auth.SecurityUtils;
import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.pay.entitlement.dto.CatalogScope;
import ai.neargo.shop.pay.entitlement.dto.OperatorEntityScope;
import java.util.HashSet;
import java.util.Set;
import org.springframework.stereotype.Component;
@Component
public class OpsEntitlementScopeResolver {
    public CatalogScope resolve(String entity) {
        var user=SecurityUtils.requireUser();
        if(user.realm()!=Realm.OPERATOR || !"MAIN".equals(user.tenantNo())) throw BizException.of(ErrorCode.FORBIDDEN);
        if(entity==null || entity.isBlank() || entity.length()>64 || !entity.equals(entity.strip())) {
            throw BizException.of(ErrorCode.BAD_REQUEST);
        }
        var scope=user.dataScope();
        boolean allowed=scope!=null && (scope.all() || scope.rules().stream().anyMatch(r ->
                ScopeDim.MERCHANT.equals(r.dim()) && r.refs()!=null && r.refs().contains(entity)));
        if(!allowed) throw BizException.of(ErrorCode.FORBIDDEN);
        return new CatalogScope(user.tenantNo(),entity,user.userNo(),true);
    }
    public OperatorEntityScope resolveList(String entityNo) {
        if (entityNo != null) {
            var exact = resolve(entityNo);
            return new OperatorEntityScope(exact.tenantNo(), exact.actorNo(), Set.of(exact.entityNo()));
        }
        var user = SecurityUtils.requireUser();
        if (user.realm() != Realm.OPERATOR || !"MAIN".equals(user.tenantNo())) {
            throw BizException.of(ErrorCode.FORBIDDEN);
        }
        var scope = user.dataScope();
        if (scope != null && scope.all()) return new OperatorEntityScope(user.tenantNo(), user.userNo(), null);
        var entities = new HashSet<String>();
        if (scope != null) scope.rules().stream().filter(rule -> ScopeDim.MERCHANT.equals(rule.dim()))
                .filter(rule -> rule.refs() != null).forEach(rule -> entities.addAll(rule.refs()));
        return new OperatorEntityScope(user.tenantNo(), user.userNo(), entities);
    }
}
