package ai.neargo.shop.commercebridge;

import ai.neargo.auth.store.SubjectKind;
import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.auth.PermChecker;
import ai.neargo.commerce.api.mall.reporting.PurchaseReporting.*;
import ai.neargo.shop.auth.*;
import java.util.*;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

/** Host-only scope creation: HTTP callers cannot supply Access or a user identity. */
@Component
public class PlatformMallAccess {
  private final PermChecker permissions;
  public PlatformMallAccess(@Qualifier("perm") PermChecker permissions) { this.permissions=permissions; }
  public record Context(Access access,String operatorNo,Set<Model> models) {
    public Context { models=Set.copyOf(models); }
  }
  public Context require(String capability,String requestedModel,boolean pending) {
    var user=SecurityUtils.requireUser();
    if(user.realm()!=Realm.OPERATOR || user.subjectKind()!=SubjectKind.OPS
       || !"MAIN".equals(user.tenantNo()) || !permissions.can(capability)) throw denied();
    var scope=user.dataScope();
    if(scope==null) throw denied();
    var refs=new HashSet<String>();
    if(scope.rules()!=null) for(var rule:scope.rules())
      if("MERCHANT".equals(rule.dim()) && rule.refs()!=null) refs.addAll(rule.refs());
    var access=new Access("MAIN",scope.all(),refs);
    var models=EnumSet.noneOf(Model.class);
    if(permissions.can("merchant:merchant:read") && (!pending || permissions.can("merchant:merchant:ban") && permissions.can("commerce:mall:offline:review"))) models.add(Model.ENTITY);
    if(permissions.can("commerce:mall:order:read") && (!pending || permissions.can("commerce:mall:offline:review"))) models.add(Model.STORE);
    if(requestedModel!=null && !requestedModel.isBlank()) {
      Model model;
      try { model=Model.valueOf(requestedModel); } catch(IllegalArgumentException e) { throw new BizException(ErrorCode.BAD_REQUEST,"Invalid model"); }
      if(!models.contains(model)) throw denied();
      models.retainAll(Set.of(model));
    }
    if(models.isEmpty()) throw denied();
    return new Context(access,user.userNo(),models);
  }
  private static BizException denied() { return new BizException(ErrorCode.FORBIDDEN,"Platform mall reporting denied"); }
}
