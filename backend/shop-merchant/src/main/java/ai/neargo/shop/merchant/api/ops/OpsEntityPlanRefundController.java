package ai.neargo.shop.merchant.api.ops;

import ai.neargo.shop.auth.*;
import ai.neargo.shop.merchant.plan.*;
import ai.neargo.shop.merchant.plan.EntityPlanPurchaseService.Actor;
import ai.neargo.commerce.api.port.*;
import ai.neargo.shop.spi.platform.AuditLogPort;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import java.util.List;

@Profile("ops") @RestController
@RequestMapping("/ops/merchant-plan-purchases/{entityNo}/{orderNo}")
@ConditionalOnProperty(name={"commerce.enabled","commerce.mall.http.enabled","commerce.mall.enabled","commerce.mall.payment.enabled","commerce.mall.offline.enabled"},havingValue="true")
public class OpsEntityPlanRefundController {
    private final EntityPlanRefundService refunds;private final AuditLogPort audit;
    public OpsEntityPlanRefundController(EntityPlanRefundService refunds,AuditLogPort audit){this.refunds=refunds;this.audit=audit;}
    private Actor actor(String entity){return new Actor(SecurityUtils.currentUserNo(),entity,null,true);}
    @GetMapping("/cash-receipts") @PreAuthorize("@perm.can('"+Perms.MERCHANT_READ+"')")
    public List<OfflineCollectionPort.CashReceipt> receipts(@PathVariable String entityNo,@PathVariable String orderNo){return refunds.receipts(actor(entityNo),orderNo);}
    @GetMapping("/cash-refunds") @PreAuthorize("@perm.can('"+Perms.MERCHANT_READ+"')")
    public List<OfflineCashRefundPort.View> list(@PathVariable String entityNo,@PathVariable String orderNo){return refunds.refunds(actor(entityNo),orderNo);}
    @PostMapping("/cash-refunds") @PreAuthorize("@perm.can('"+Perms.MERCHANT_BAN+"') and @perm.can('commerce:mall:offline:refund:confirm')")
    public OfflineCashRefundPort.View request(@PathVariable String entityNo,@PathVariable String orderNo,@RequestBody OfflineCashRefundPort.Request c){var r=refunds.request(actor(entityNo),orderNo,c);audit.record("PLAN_CASH_REFUND_REQUEST",orderNo,r.refundNo()+"|"+r.status(),true);return r;}
    @PostMapping("/cash-refunds/{refundNo}/payout") @PreAuthorize("@perm.can('"+Perms.MERCHANT_BAN+"') and @perm.can('commerce:mall:offline:refund:confirm')")
    public OfflineCashRefundPort.View payout(@PathVariable String entityNo,@PathVariable String orderNo,@PathVariable String refundNo,@RequestBody OfflineCashRefundPort.Payout c){var r=refunds.payout(actor(entityNo),orderNo,refundNo,c);audit.record("PLAN_CASH_REFUND_PAYOUT",orderNo,r.refundNo()+"|"+r.status(),true);return r;}
    @PostMapping("/cash-refunds/{refundNo}/recover") @PreAuthorize("@perm.can('"+Perms.MERCHANT_BAN+"') and @perm.can('commerce:mall:offline:refund:confirm')")
    public OfflineCashRefundPort.View recover(@PathVariable String entityNo,@PathVariable String orderNo,@PathVariable String refundNo){return refunds.recover(actor(entityNo),orderNo,refundNo);}
}
