package ai.neargo.shop.pay.api.ops;

import ai.neargo.shop.auth.Perms;
import ai.neargo.shop.auth.SecurityUtils;
import ai.neargo.shop.pay.acquiring.service.PayOpsQueryService;
import ai.neargo.shop.pay.acquiring.service.PayOpsQueryService.NotifyRow;
import ai.neargo.shop.pay.notify.service.BizNotifyService;
import java.util.List;
import org.springframework.context.annotation.Profile;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 答复记录与手动重推（F53）：放弃了的 webhook 运营一键重推，投递记录记 MANUAL 与操作人 */
@Profile("ops")
@RestController
@RequestMapping("/pay-ops/v1/notifies")
public class PayOpsNotifyController {

    private final PayOpsQueryService query;
    private final BizNotifyService notifier;

    public PayOpsNotifyController(PayOpsQueryService query, BizNotifyService notifier) {
        this.query = query;
        this.notifier = notifier;
    }

    @PreAuthorize("@perm.can('" + Perms.PAY_LOG_VIEW + "')")
    @GetMapping
    public List<NotifyRow> list(@RequestParam(required = false) String status,
                                @RequestParam(defaultValue = "50") int limit) {
        return query.notifies(status, limit);
    }

    @PreAuthorize("@perm.can('" + Perms.PAY_NOTIFY_RESEND + "')")
    @PostMapping("/{id}/resend")
    public void resend(@PathVariable Long id) {
        notifier.redeliver(id, SecurityUtils.currentUserNo());
    }
}
