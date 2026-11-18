package ai.neargo.shop.portal.mp;

import ai.neargo.shop.auth.Realm;
import ai.neargo.shop.auth.SecurityUtils;
import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.member.dto.MemberCodeViews.MemberCodeView;
import ai.neargo.shop.member.service.MemberCodeResolveService;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

/** One consumer credential for the whole membership experience; never accepts a target user from the client. */
@Profile("api")
@RestController
public class MpMemberCodeController {
    private final MemberCodeResolveService service;

    public MpMemberCodeController(MemberCodeResolveService service) {
        this.service = service;
    }

    @PostMapping("/mp/member-code")
    public MemberCodeView issue() {
        var user = SecurityUtils.requireUser();
        if (user.realm() != Realm.CONSUMER) {
            throw BizException.of(ErrorCode.FORBIDDEN);
        }
        return service.issue(user.tenantNo(), user.userNo());
    }
}
