package ai.neargo.shop.product.api.biz;

import ai.neargo.shop.auth.BizPerms;
import ai.neargo.shop.product.dto.GoodsPricingPolicyCommands.PolicyVO;
import ai.neargo.shop.product.dto.GoodsPricingPolicyCommands.SaveReq;
import ai.neargo.shop.product.policy.GoodsPricingPolicyAccessPolicy;
import ai.neargo.shop.product.service.GoodsPricingPolicyService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.context.annotation.Profile;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

/** Actual product policy management; no order prices or trusted identities accepted from the client. */
@Profile("api")
@RestController
@Validated
public class BizGoodsPricingPolicyController {
    private final GoodsPricingPolicyService service;
    private final GoodsPricingPolicyAccessPolicy access;
    public BizGoodsPricingPolicyController(GoodsPricingPolicyService service, GoodsPricingPolicyAccessPolicy access) {
        this.service = service; this.access = access;
    }
    @GetMapping("/biz/goods/{goodsNo}/skus/{skuNo}/pricing-policy")
    @PreAuthorize("@perm.canBiz('" + BizPerms.STOCK + "')")
    public PolicyVO get(@PathVariable String goodsNo, @PathVariable String skuNo) {
        return service.get(access.context(false), goodsNo, skuNo);
    }
    @PutMapping("/biz/goods/{goodsNo}/skus/{skuNo}/pricing-policy")
    @PreAuthorize("@perm.canBiz('" + BizPerms.STOCK + "')")
    public PolicyVO save(@PathVariable String goodsNo, @PathVariable String skuNo, @Valid @RequestBody SaveReq request,
            @RequestHeader("Idempotency-Key") @NotBlank @Size(max = 128) String key) {
        return service.save(access.context(true), goodsNo, skuNo, request, key);
    }
}
