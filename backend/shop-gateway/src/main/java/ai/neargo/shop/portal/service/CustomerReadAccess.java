package ai.neargo.shop.portal.service;

import ai.neargo.auth.store.SubjectKind;
import ai.neargo.shop.auth.PermChecker;
import ai.neargo.shop.auth.Realm;
import ai.neargo.shop.auth.SecurityUtils;
import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import java.util.HashSet;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

@Component
public class CustomerReadAccess {
  private final PermChecker permissions;
  public CustomerReadAccess(@Qualifier("perm") PermChecker permissions) { this.permissions = permissions; }
  public CustomerReportingService.Scope require(String permission) {
    var user = SecurityUtils.requireUser();
    if (user.realm() != Realm.OPERATOR || user.subjectKind() != SubjectKind.OPS
        || !"MAIN".equals(user.tenantNo()) || !permissions.can(permission) || user.dataScope() == null) {
      throw BizException.of(ErrorCode.FORBIDDEN);
    }
    var refs = new HashSet<String>();
    if (user.dataScope().rules() != null) for (var rule : user.dataScope().rules()) {
      if ("MERCHANT".equals(rule.dim()) && rule.refs() != null) refs.addAll(rule.refs());
    }
    return new CustomerReportingService.Scope(user.tenantNo(), user.dataScope().all(), refs);
  }
}
