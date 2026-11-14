package ai.neargo.shop.commerce.api.biz;

import ai.neargo.shop.auth.BizPerms;
import ai.neargo.shop.commerce.api.CommerceRequestScope;
import ai.neargo.shop.commerce.config.ConditionalOnCommerceWebEnabled;
import ai.neargo.shop.commerce.dto.ProductProfileVO;
import ai.neargo.shop.commerce.dto.ProviderVO;
import ai.neargo.shop.commerce.service.ProviderService;
import ai.neargo.shop.common.PageData;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import org.springframework.context.annotation.Profile;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Set;

/** 当前目录只读；协议声明不授予真实调用能力。 */
@RestController
@Profile("api")
@ConditionalOnCommerceWebEnabled
@Validated
@PreAuthorize("@perm.canBiz('" + BizPerms.STORE_ADMIN + "')")
public class BizChannelProviderController {
    private final ProviderService service;
    private final CommerceRequestScope scope;

    public BizChannelProviderController(ProviderService service, CommerceRequestScope scope) {
        this.service = service;
        this.scope = scope;
    }

    @GetMapping("/biz/x/commerce/providers")
    public PageData<ProviderVO> listProviders(
            @RequestParam(value = "page", defaultValue = "1") int page,
            @RequestParam(value = "size", defaultValue = "20") int size,
            HttpServletRequest request) {
        CommerceRequestScope.query(request, Set.of("page", "size"));
        scope.owner();
        return service.listProviders(page, size);
    }

    @GetMapping("/biz/x/commerce/providers/{providerNo}/profiles")
    public PageData<ProductProfileVO> listProfiles(
            @PathVariable("providerNo") @NotBlank @Size(max = 32) String number,
            @RequestParam(value = "page", defaultValue = "1") int page,
            @RequestParam(value = "size", defaultValue = "20") int size,
            HttpServletRequest request) {
        CommerceRequestScope.query(request, Set.of("page", "size"));
        scope.owner();
        return service.listProfiles(number, page, size);
    }
}
