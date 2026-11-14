package ai.neargo.shop.pay.api.biz;

import ai.neargo.shop.auth.BizPerms;
import ai.neargo.shop.pay.openapp.service.AppGrantService;
import ai.neargo.shop.pay.openapp.service.AppGrantService.GrantView;
import java.util.List;
import java.util.Set;
import org.springframework.context.annotation.Profile;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 授权第三方代本店操作（F44）：按 AppId 授权、看列表、撤销（即时生效） */
@Profile("api")
@RestController
@RequestMapping("/biz/pay/app-grant")
public class BizPayAppGrantController {

    private final AppGrantService grants;
    private final BizPayContext ctx;

    public BizPayAppGrantController(AppGrantService grants, BizPayContext ctx) {
        this.grants = grants;
        this.ctx = ctx;
    }

    record GrantReq(String appNo, Set<String> actions) {
    }

    @PreAuthorize("@perm.canBiz('" + BizPerms.PAY_APP_GRANT + "')")
    @GetMapping
    public List<GrantView> list() {
        Long storeId = ctx.storeId();
        return grants.ofStore(ctx.caller(storeId), storeId);
    }

    @PreAuthorize("@perm.canBiz('" + BizPerms.PAY_APP_GRANT + "')")
    @PostMapping
    public GrantView grant(@RequestBody GrantReq req) {
        Long storeId = ctx.storeId();
        return grants.grant(ctx.caller(storeId), storeId, req.appNo(), req.actions());
    }

    @PreAuthorize("@perm.canBiz('" + BizPerms.PAY_APP_GRANT + "')")
    @DeleteMapping("/{id}")
    public void revoke(@PathVariable Long id) {
        Long storeId = ctx.storeId();
        grants.revoke(ctx.caller(storeId), id);
    }
}
