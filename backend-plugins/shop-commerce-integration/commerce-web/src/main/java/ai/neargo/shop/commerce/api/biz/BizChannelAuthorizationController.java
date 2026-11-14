package ai.neargo.shop.commerce.api.biz;

import ai.neargo.shop.auth.BizPerms;
import ai.neargo.shop.auth.SecurityUtils;
import ai.neargo.shop.commerce.api.CommerceRequestScope;
import ai.neargo.shop.commerce.config.ConditionalOnCommerceWebEnabled;
import ai.neargo.shop.commerce.dto.AuthorizationActionVO;
import ai.neargo.shop.commerce.dto.AuthorizationRevisionVO;
import ai.neargo.shop.commerce.dto.AuthorizeChannelAccountReq;
import ai.neargo.shop.commerce.dto.OperationVO;
import ai.neargo.shop.commerce.dto.RevokeChannelAuthorizationReq;
import ai.neargo.shop.commerce.service.ChannelAuthorizationService;
import ai.neargo.shop.common.PageData;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import org.springframework.context.annotation.Profile;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Set;

/** 账号授权影响整个主体；属主权限与原账号归属均在可信上下文内核对。 */
@RestController
@Profile("api")
@ConditionalOnCommerceWebEnabled
@Validated
@PreAuthorize("@perm.canBiz('" + BizPerms.STORE_ADMIN + "')")
public class BizChannelAuthorizationController {
    private final ChannelAuthorizationService service;
    private final CommerceRequestScope scope;

    public BizChannelAuthorizationController(
            ChannelAuthorizationService service, CommerceRequestScope scope) {
        this.service = service;
        this.scope = scope;
    }

    @PostMapping("/biz/x/commerce/accounts/{accountNo}:authorize")
    public AuthorizationActionVO authorize(
            @PathVariable("accountNo") @NotBlank @Size(max = 32) String accountNo,
            @Valid @RequestBody AuthorizeChannelAccountReq req,
            @RequestHeader("Idempotency-Key") @NotBlank @Size(max = 128) String key,
            HttpServletRequest request) {
        CommerceRequestScope.query(request, Set.of());
        return service.authorize(scope.owner(), accountNo, SecurityUtils.currentUserNo(), req, key);
    }

    @PostMapping("/biz/x/commerce/accounts/{accountNo}:revoke")
    public OperationVO revoke(
            @PathVariable("accountNo") @NotBlank @Size(max = 32) String accountNo,
            @Valid @RequestBody RevokeChannelAuthorizationReq req,
            @RequestHeader("Idempotency-Key") @NotBlank @Size(max = 128) String key,
            HttpServletRequest request) {
        CommerceRequestScope.query(request, Set.of());
        return service.revoke(scope.owner(), accountNo, SecurityUtils.currentUserNo(), req, key);
    }

    @GetMapping("/biz/x/commerce/accounts/{accountNo}/authorization-revisions")
    public PageData<AuthorizationRevisionVO> listRevisions(
            @PathVariable("accountNo") @NotBlank @Size(max = 32) String accountNo,
            @RequestParam(value = "page", defaultValue = "1") int page,
            @RequestParam(value = "size", defaultValue = "20") int size,
            HttpServletRequest request) {
        CommerceRequestScope.query(request, Set.of("page", "size"));
        return service.listRevisions(scope.owner(), accountNo, page, size);
    }
}
