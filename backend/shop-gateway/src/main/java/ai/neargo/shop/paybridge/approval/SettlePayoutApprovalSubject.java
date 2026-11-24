package ai.neargo.shop.paybridge.approval;

import ai.neargo.shop.auth.Perms;
import ai.neargo.shop.pay.PayoutService;
import ai.neargo.shop.pay.SettleBatchService;
import ai.neargo.shop.pay.SettleBatchService.BatchRevision;
import ai.neargo.shop.pay.entity.StlSettleBatch;
import ai.neargo.shop.spi.approval.ApprovalApplyException;
import ai.neargo.shop.spi.approval.ApprovalSubject;
import ai.neargo.shop.spi.platform.AuditLogPort;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * T7b · 结算批次放款接入审批（方案-第一批审批工作流 T7）。三级 · 资金：审核 → 复核，均 {@code finance:payout:execute}。
 * 修订号 = 批次 {@code updated_at}（出款失败退回 RECONCILED 时会变）。生效 = 原「放款」（三道闸在执行时重检，
 * 不过 → 生效失败，如发票未核、出款账户缺失）。审批未通过 = 批次留在 RECONCILED，不动。
 */
@Component
public class SettlePayoutApprovalSubject implements ApprovalSubject {

    public static final String SUBJECT_TYPE = "shop.settle_payout";

    private final SettleBatchService batches;
    private final PayoutService payouts;
    private final AuditLogPort audit;

    public SettlePayoutApprovalSubject(SettleBatchService batches, PayoutService payouts, AuditLogPort audit) {
        this.batches = batches;
        this.payouts = payouts;
        this.audit = audit;
    }

    @Override public String subjectType() { return SUBJECT_TYPE; }
    @Override public String processCode() { return "shop_settle_payout"; }
    @Override public String processName() { return "结算批次放款"; }
    @Override public String approverPerm() { return Perms.FINANCE_PAYOUT_EXECUTE; }
    @Override public String recheckPerm() { return Perms.FINANCE_PAYOUT_EXECUTE; }
    @Override public Level level() { return Level.FUNDS; }

    @Override
    public Snapshot snapshot(String batchNo) {
        var b = find(batchNo).batch();
        return new Snapshot("结算放款 · " + batchNo + " · " + PayApprovalSupport.money(b.netMinor()), "商户 " + b.entityNo(),
                Set.of("merchant:" + b.entityNo()), Map.of("netMinor", b.netMinor()),
                SettleFacts.of(b, List.of(Fact.change("批次状态", "可放款", "已放款"))));
    }

    @Override
    public void apply(String batchNo, long revision) {
        BatchRevision r = find(batchNo);
        String status = r.batch().status();
        if (StlSettleBatch.RELEASED.equals(status)) return; // 重投
        if (!StlSettleBatch.RECONCILED.equals(status) || r.revision() != revision) {
            throw new ApprovalApplyException(ApprovalApplyException.STALE, "批次已变化：status=" + status);
        }
        try {
            var made = PayApprovalSupport.asApprovalService(() -> payouts.releaseBatch(batchNo, PayApprovalSupport.ACTOR));
            audit.record("SETTLE_BATCH_RELEASE", batchNo, "审批通过放款：生成 %d 笔 · 合计 %d 分".formatted(made.size(),
                    made.stream().mapToLong(PayoutService.PayoutVO::amountMinor).sum()), true);
        } catch (RuntimeException e) {
            throw PayApprovalSupport.failure(e);
        }
    }

    @Override
    public void onClosed(String batchNo, long revision, Outcome outcome, String reason) {
        // 放款未获批：批次留在 RECONCILED，可再次申请
    }

    BatchRevision find(String batchNo) {
        return batches.revision(batchNo).orElseThrow(() -> new ApprovalApplyException("NOT_FOUND", "结算批次不存在：" + batchNo));
    }
}
