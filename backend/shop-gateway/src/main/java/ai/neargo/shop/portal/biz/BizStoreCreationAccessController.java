package ai.neargo.shop.portal.biz;

import ai.neargo.shop.auth.SecurityUtils;
import ai.neargo.shop.merchant.service.StoreCreationAccessService;
import org.springframework.context.annotation.Profile;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Profile("api")
public class BizStoreCreationAccessController {
    private final StoreCreationAccessService service;
    public BizStoreCreationAccessController(StoreCreationAccessService service){this.service=service;}
    @PreAuthorize("@perm.merchantAccount()")
    @GetMapping("/biz/merchant/store-creation-context")
    public StoreCreationAccessService.Access access(){return service.access(SecurityUtils.requireUser());}
}
