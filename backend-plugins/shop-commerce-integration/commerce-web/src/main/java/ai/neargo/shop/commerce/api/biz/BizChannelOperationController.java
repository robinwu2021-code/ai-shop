package ai.neargo.shop.commerce.api.biz;

import ai.neargo.shop.auth.BizPerms;
import ai.neargo.shop.commerce.api.CommerceRequestScope;
import ai.neargo.shop.commerce.config.ConditionalOnCommerceWebEnabled;
import ai.neargo.shop.commerce.dto.OperationVO;
import ai.neargo.shop.commerce.service.ChannelOperationService;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import org.springframework.context.annotation.Profile;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

import java.util.Set;

/** 原动作查询只读当前事实，不能借GET重复发送授权或撤权。 */
@RestController
@Profile("api")
@ConditionalOnCommerceWebEnabled
@Validated
@PreAuthorize("@perm.canBiz('" + BizPerms.STORE_ADMIN + "')")
public class BizChannelOperationController {
    private final ChannelOperationService service;
    private final CommerceRequestScope scope;

    public BizChannelOperationController(
            ChannelOperationService service, CommerceRequestScope scope) {
        this.service = service;
        this.scope = scope;
    }

    @GetMapping("/biz/x/commerce/operations/{operationNo}")
    public OperationVO getOperation(
            @PathVariable("operationNo") @NotBlank @Size(max = 32) String operationNo,
            HttpServletRequest request) {
        CommerceRequestScope.query(request, Set.of());
        return service.getOperation(scope.owner(), operationNo);
    }
}
