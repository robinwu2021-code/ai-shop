package ai.neargo.shop.portal.biz;

import ai.neargo.shop.auth.BizContext;
import ai.neargo.shop.auth.BizPerms;
import ai.neargo.shop.auth.SecurityUtils;
import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.common.PageData;
import ai.neargo.shop.paybridge.EntitlementScopeResolver;
import ai.neargo.shop.paybridge.StoredValueMemberSelectionService;
import ai.neargo.shop.paybridge.dto.StoredValueCustomerViews.*;
import ai.neargo.shop.spi.entitlement.EntitlementContext;
import ai.neargo.shop.spi.entitlement.EntitlementDefinition.RedemptionScope;
import java.util.List;
import org.springframework.context.annotation.Profile;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@Profile("api")
@RestController
public class BizStoredValueCustomerController {
    private final StoredValueMemberSelectionService customers;
    private final EntitlementScopeResolver scopes;
    public BizStoredValueCustomerController(StoredValueMemberSelectionService customers,EntitlementScopeResolver scopes){
        this.customers=customers;this.scopes=scopes;
    }
    @PreAuthorize("@perm.canBiz('biz:customer') and @perm.canBiz('pay:sv:recharge:create')")
    @GetMapping("/biz/stored-value/customer-candidates")
    public PageData<Candidate> candidates(@RequestParam String storeNo,@RequestParam String phone){
        require(storeNo);var rows=customers.candidates(context(),BizContext.requireMerchantNo(),storeNo,phone);
        if(BizContext.current().withStore(storeNo).can(BizPerms.SV_ACCOUNT_READ))return rows;
        return new PageData<>(rows.records().stream().map(c->new Candidate(c.memberNo(),c.displayName(),c.phoneMasked(),c.memberStatus(),c.canRecharge(),c.unavailableReason(),null)).toList(),rows.total(),rows.page(),rows.size());
    }
    @PreAuthorize("@perm.canBiz('biz:customer') and @perm.canBiz('pay:sv:recharge:create')")
    @PostMapping("/biz/stored-value/customer-selections")
    public Selection select(@RequestBody SelectRequest request){
        require(request.storeNo());boolean read=BizContext.current().withStore(request.storeNo()).can(BizPerms.SV_ACCOUNT_READ);
        if("ACCOUNT".equals(request.source().type())&&!read)throw BizException.of(ErrorCode.FORBIDDEN);
        var selected=customers.select(context(),BizContext.requireMerchantNo(),request.storeNo(),request.source());
        return read?selected:new Selection(selected.customerRef(),selected.expiresAt(),selected.memberNo(),selected.displayName(),selected.phoneMasked(),null);
    }
    private void require(String store){
        scopes.resolve(new RedemptionScope("STORES",List.of(store)));
        var scope=BizContext.current().withStore(store);
        if(!scope.can(BizPerms.CUSTOMER)||!scope.can(BizPerms.SV_RECHARGE_CREATE))throw BizException.of(ErrorCode.FORBIDDEN);
    }
    private static EntitlementContext context(){var u=SecurityUtils.requireUser();return new EntitlementContext(u.tenantNo(),u.userNo(),"member-select","0".repeat(64));}
}
