package ai.neargo.shop.pay.acquiring.policy;

import java.util.Set;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/**
 * 退款复核由谁做（TDD-基础能力库对齐 §2.2 1e，Q12）：主题 {@link #SUBJECT_TYPE} 在审批服务里启用时由审批服务做，
 * 支付不再开 {@code pay_approval}；否则仍是支付自己的两人复核。
 *
 * <p>读的是审批服务自己的开关（{@code shop.opskit.enabled} 与 {@code shop.opskit.enabled-subjects}），不另设一个：
 * 两个开关各管一半时，一边开一边关就会出现“支付等审批服务、审批服务没收到”的退款。
 * 支付不依赖审批端口（独立部署时没有它），只读配置。
 */
@Component
public class RefundReviewRouting {

    /** 审批主题；与网关的 {@code PayRefundReviewApprovalSubject} 一致 */
    public static final String SUBJECT_TYPE = "shop.pay_refund_review";

    private final boolean external;

    public RefundReviewRouting(Environment env) {
        Binder binder = Binder.get(env);
        boolean opskit = binder.bind("shop.opskit.enabled", Boolean.class).orElse(false);
        Set<String> subjects = binder.bind("shop.opskit.enabled-subjects", Bindable.setOf(String.class)).orElse(Set.of());
        this.external = opskit && subjects.contains(SUBJECT_TYPE);
    }

    /** true = 复核在审批服务，结果经 {@code RefundService#applyApproval} 回写 */
    public boolean external() {
        return external;
    }
}
