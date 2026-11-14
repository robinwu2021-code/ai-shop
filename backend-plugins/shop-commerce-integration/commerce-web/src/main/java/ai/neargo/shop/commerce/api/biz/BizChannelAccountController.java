package ai.neargo.shop.commerce.api.biz;

import ai.neargo.shop.auth.BizPerms;
import ai.neargo.shop.auth.SecurityUtils;
import ai.neargo.shop.commerce.api.CommerceRequestScope;
import ai.neargo.shop.commerce.config.ConditionalOnCommerceWebEnabled;
import ai.neargo.shop.commerce.dto.ChannelAccountVO;
import ai.neargo.shop.commerce.dto.CreateChannelAccountReq;
import ai.neargo.shop.commerce.service.ChannelAccountService;
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

/** 账号配置影响全主体，首批沿原STORE_ADMIN并显式要求属主，不接受调用方自报主体。 */
@RestController
@Profile("api")
@ConditionalOnCommerceWebEnabled
@Validated
@PreAuthorize("@perm.canBiz('" + BizPerms.STORE_ADMIN + "')")
public class BizChannelAccountController {
    private final ChannelAccountService service;
    private final CommerceRequestScope scope;

    public BizChannelAccountController(ChannelAccountService service, CommerceRequestScope scope) {
        this.service = service;
        this.scope = scope;
    }

    @PostMapping("/biz/x/commerce/accounts")
    public ChannelAccountVO createAccount(
            @Valid @RequestBody CreateChannelAccountReq req,
            @RequestHeader("Idempotency-Key") @NotBlank @Size(max = 128) String key,
            HttpServletRequest request) {
        CommerceRequestScope.query(request, Set.of());
        return service.createAccount(scope.owner(), SecurityUtils.currentUserNo(), req, key);
    }

    @GetMapping("/biz/x/commerce/accounts")
    public PageData<ChannelAccountVO> listAccounts(
            @RequestParam(value = "page", defaultValue = "1") int page,
            @RequestParam(value = "size", defaultValue = "20") int size,
            HttpServletRequest request) {
        CommerceRequestScope.query(request, Set.of("page", "size"));
        return service.listAccounts(scope.owner(), page, size);
    }

    @GetMapping("/biz/x/commerce/accounts/{accountNo}")
    public ChannelAccountVO getAccount(
            @PathVariable("accountNo") @NotBlank @Size(max = 32) String accountNo,
            HttpServletRequest request) {
        CommerceRequestScope.query(request, Set.of());
        return service.getAccount(scope.owner(), accountNo);
    }
}
