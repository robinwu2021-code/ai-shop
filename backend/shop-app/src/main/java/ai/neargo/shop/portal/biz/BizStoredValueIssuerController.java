package ai.neargo.shop.portal.biz;

import ai.neargo.shop.merchant.brand.IssuerAuthorityService;
import ai.neargo.shop.merchantbridge.ManagementAccess;
import ai.neargo.shop.payclient.StoredValueIssuerAppService;
import ai.neargo.shop.spi.storedvalue.StoredValueIssuers.*;
import org.springframework.context.annotation.Profile;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@Profile("api")
@RestController
@PreAuthorize("@managementAccess.authenticated()")
public class BizStoredValueIssuerController {
    private final ManagementAccess access;
    private final IssuerAuthorityService authority;
    private final StoredValueIssuerAppService issuers;
    public BizStoredValueIssuerController(ManagementAccess access,IssuerAuthorityService authority,StoredValueIssuerAppService issuers) {
        this.access=access;this.authority=authority;this.issuers=issuers;
    }
    public record ConfigureRequest(String managementNo,Config configuration) { }
    @GetMapping("/biz/management/brands/{brandNo}/stored-value/issuer")
    public Lookup brand(@PathVariable String brandNo,@RequestParam String managementNo) {
        return issuers.find(authority.readScope(access.actor(),managementNo,"BRAND",brandNo));
    }
    @PostMapping("/biz/management/brands/{brandNo}/stored-value/issuer")
    public View create(@PathVariable String brandNo,@RequestBody ConfigureRequest request,@RequestHeader("Idempotency-Key") String key) {
        return issuers.configure(authority.configureScope(access.actor(),request.managementNo(),"BRAND",brandNo,request.configuration()),request.configuration(),key);
    }
}
