package ai.neargo.shop.merchant.api.biz;

import ai.neargo.shop.auth.*;
import ai.neargo.shop.merchant.plan.*;
import ai.neargo.shop.merchant.plan.EntityPlanPurchaseService.Actor;
import ai.neargo.commerce.api.port.OfflineCashRefundPort;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import java.util.List;

@Profile("api") @RestController
@ConditionalOnProperty(name={"commerce.enabled","commerce.mall.http.enabled","commerce.mall.enabled","commerce.mall.payment.enabled","commerce.mall.offline.enabled"},havingValue="true")
public class BizEntityPlanRefundController {
    private final EntityPlanRefundService refunds;
    public BizEntityPlanRefundController(EntityPlanRefundService refunds){this.refunds=refunds;}
    @GetMapping("/biz/plan/purchases/{orderNo}/cash-refunds") @PreAuthorize("@perm.canBiz('"+BizPerms.STORE_ADMIN+"')")
    public List<OfflineCashRefundPort.View> list(@PathVariable String orderNo){return refunds.refunds(new Actor(SecurityUtils.currentUserNo(),BizContext.requireMerchantNo(),BizContext.requireStoreNo(),false,SecurityUtils.requireUser().subjectKind().name()),orderNo);}
}
