package ai.neargo.shop.merchantbridge;
import ai.neargo.shop.auth.*;
import ai.neargo.shop.common.*;
import ai.neargo.shop.merchant.brand.ManagementModels.Actor;
import org.springframework.stereotype.Component;
@Component("managementAccess")
public class ManagementAccess {
 public boolean authenticated(){return SecurityUtils.currentUser().filter(u->u.realm()==Realm.MERCHANT).isPresent();}
 public Actor actor(){var user=SecurityUtils.currentUser().filter(u->u.realm()==Realm.MERCHANT).orElseThrow(()->BizException.of(ErrorCode.UNAUTHORIZED));String kind=user.subjectKind().name();if(!kind.equals("MCH")&&!kind.equals("USR"))throw BizException.of(ErrorCode.FORBIDDEN);return new Actor(user.tenantNo(),(kind.equals("MCH")?"MCH:":"USER:")+user.userNo());}
}
