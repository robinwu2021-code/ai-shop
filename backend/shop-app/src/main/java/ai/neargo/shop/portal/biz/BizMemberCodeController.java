package ai.neargo.shop.portal.biz;

import ai.neargo.shop.auth.BizContext;
import ai.neargo.shop.auth.BizPerms;
import ai.neargo.shop.auth.Realm;
import ai.neargo.shop.auth.SecurityUtils;
import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.member.dto.MemberCodeScope;
import ai.neargo.shop.member.dto.MemberCodeViews.CustomerReference;
import ai.neargo.shop.member.dto.MemberResolveCommand;
import ai.neargo.shop.member.service.MemberCodeResolveService;
import jakarta.validation.Valid;
import org.springframework.context.annotation.Profile;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/** Resolving a scan identifies a customer; it creates no sale, account, authorization or debit. */
@Profile("api")
@RestController
@Validated
public class BizMemberCodeController {
    private final MemberCodeResolveService service;

    public BizMemberCodeController(MemberCodeResolveService service) {
        this.service = service;
    }

    @PreAuthorize("@perm.canBiz('" + BizPerms.CUSTOMER + "')")
    @PostMapping("/biz/member-code/resolve")
    public CustomerReference resolve(@Valid @RequestBody MemberResolveCommand request) {
        var user = SecurityUtils.requireUser();
        if (user.realm() != Realm.MERCHANT) {
            throw BizException.of(ErrorCode.FORBIDDEN);
        }
        String entityNo = BizContext.requireMerchantNo();
        var allowedStores = BizContext.current().allowedStoresOrAll();
        if (allowedStores != null && !allowedStores.contains(request.storeNo())) {
            throw BizException.of(ErrorCode.NOT_FOUND);
        }
        return service.resolve(new MemberCodeScope(user.tenantNo(), entityNo, request.storeNo(), user.userNo()),
                request.memberCode());
    }
}
