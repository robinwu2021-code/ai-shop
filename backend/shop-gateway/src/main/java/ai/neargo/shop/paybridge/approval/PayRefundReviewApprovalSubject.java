package ai.neargo.shop.paybridge.approval;

import ai.neargo.shop.auth.Perms;
import ai.neargo.shop.pay.acquiring.policy.RefundReviewRouting;
import ai.neargo.shop.pay.acquiring.service.PayOpsQueryService.RefundRow;
import ai.neargo.shop.payclient.OpsPaymentAppService;
import ai.neargo.shop.spi.approval.ApprovalApplyException;
import ai.neargo.shop.spi.approval.ApprovalSubject;
import ai.neargo.shop.spi.user.MerchantQueryPort;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * 退款两人复核接入审批（TDD-基础能力库对齐 §2.2 1e，Q12）。三级 · 资金：业务审核 → 复核，均 {@code pay:refund:review}；
 * {@link #separateApprovers()}：提交人（退款申请人）不得审批、两步不同人 —— 即原 {@code pay_approval} 的三人互不相同。
 *
 * <p>修订号 = 退款单创建时间（待复核期间退款单不变）。生效 = 放行，未通过（驳回 / 撤回 / 放弃）= 驳回：
 * 待复核的退款不能无限挂着，执行行要判失败、占用要释放，售后侧据此知道没退成。
 */
@Component
public class PayRefundReviewApprovalSubject implements ApprovalSubject {

    public static final String SUBJECT_TYPE = RefundReviewRouting.SUBJECT_TYPE;
    /** 待复核的两个状态 */
    static final List<String> PENDING = List.of("PENDING_REVIEW", "PENDING_SECOND_REVIEW");
    /** 找待复核退款时一次最多看多少条；超出的下一轮补送 */
    static final int SCAN_LIMIT = 500;

    private final OpsPaymentAppService app;
    private final MerchantQueryPort merchants;

    public PayRefundReviewApprovalSubject(OpsPaymentAppService app, MerchantQueryPort merchants) {
        this.app = app;
        this.merchants = merchants;
    }

    @Override public String subjectType() { return SUBJECT_TYPE; }
    @Override public String processCode() { return "shop_pay_refund_review"; }
    @Override public String processName() { return "退款复核"; }
    @Override public String approverPerm() { return Perms.PAY_REFUND_REVIEW; }
    @Override public String recheckPerm() { return Perms.PAY_REFUND_REVIEW; }
    @Override public Level level() { return Level.FUNDS; }
    @Override public boolean separateApprovers() { return true; }

    /** 待复核的退款（给补送任务用） */
    List<RefundRow> pending() {
        return PayApprovalSupport.asApprovalService(() -> PENDING.stream()
                .flatMap(s -> app.refunds(s, SCAN_LIMIT).stream()).toList());
    }

    static long revisionOf(RefundRow r) {
        return r.createdAt() == null ? 0 : r.createdAt();
    }

    @Override
    public Snapshot snapshot(String payRefundNo) {
        RefundRow r = find(payRefundNo);
        String entityNo = PayApprovalSupport.asApprovalService(() -> Optional.ofNullable(r.payOrderNo())
                .map(app::order).map(d -> d.order().storeId()).flatMap(merchants::payStoreById)
                .map(MerchantQueryPort.PayStore::entityNo).orElse(null));
        Set<String> scopes = entityNo == null ? Set.of() : Set.of("merchant:" + entityNo);
        return new Snapshot("退款复核 · " + payRefundNo + " · " + PayApprovalSupport.money(r.amountMinor()) + " " + r.currency(),
                "支付单 " + r.payOrderNo(), scopes, Map.of("amountMinor", r.amountMinor()),
                List.of(Fact.of("支付单", String.valueOf(r.payOrderNo())),
                        Fact.of("退款金额", PayApprovalSupport.money(r.amountMinor()) + " " + r.currency()),
                        Fact.of("退款原因", String.valueOf(r.reason())),
                        Fact.of("申请人", String.valueOf(r.requestedBy())),
                        Fact.of("命中规则", String.valueOf(r.hitReasons())),
                        Fact.change("退款状态", "待复核", "放行执行")));
    }

    @Override
    public void apply(String payRefundNo, long revision) {
        decide(payRefundNo, true, "审批通过");
    }

    @Override
    public void onClosed(String payRefundNo, long revision, Outcome outcome, String reason) {
        decide(payRefundNo, false, reason == null || reason.isBlank() ? "审批未通过（" + outcome + "）" : reason);
    }

    private void decide(String payRefundNo, boolean approve, String reason) {
        try {
            PayApprovalSupport.asApprovalService(() -> app.applyRefundApproval(payRefundNo, approve, reason));
        } catch (RuntimeException e) {
            throw PayApprovalSupport.failure(e);
        }
    }

    /** 先在待复核里找；已被处理过的（重投、补快照）到最近的退款里找 */
    private RefundRow find(String payRefundNo) {
        return pending().stream().filter(r -> payRefundNo.equals(r.payRefundNo())).findFirst()
                .or(() -> PayApprovalSupport.asApprovalService(() -> app.refunds(null, SCAN_LIMIT)).stream()
                        .filter(r -> payRefundNo.equals(r.payRefundNo())).findFirst())
                .orElseThrow(() -> new ApprovalApplyException("NOT_FOUND", "退款单不存在：" + payRefundNo));
    }
}
