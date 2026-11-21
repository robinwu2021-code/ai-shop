package ai.neargo.shop.portal.biz;

import ai.neargo.auth.store.SubjectKind;
import ai.neargo.shop.auth.SecurityUtils;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Validated
@Profile("api")
@ConditionalOnProperty(name = "shop.pay.biz-onboarding.enabled", havingValue = "true")
public class BizPaySessionController {
  public record SessionVO(
      String realm, String subjectType, String subjectRef, String tenantNo, String status) {}

  @GetMapping("/biz/auth/session")
  public ResponseEntity<SessionVO> session() {
    var user = SecurityUtils.requireUser();
    return ResponseEntity.ok()
        .cacheControl(CacheControl.noStore())
        .body(
            new SessionVO(
                user.realm().name(),
                user.subjectKind() == SubjectKind.USR ? "USER" : "MCH_ACCOUNT",
                user.userNo(),
                user.tenantNo(),
                "ACTIVE"));
  }
}
