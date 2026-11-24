package ai.neargo.shop.paybridge.approval;

import ai.neargo.shop.auth.Perms;
import ai.neargo.shop.pay.points.admin.PointsAdjustmentService;
import ai.neargo.shop.pay.points.admin.PointsAdjustmentService.Review;
import ai.neargo.shop.pay.points.admin.PointsAdjustmentService.View;
import ai.neargo.shop.payclient.PointsOpsErrors;
import ai.neargo.shop.spi.approval.ApprovalApplyException;
import ai.neargo.shop.spi.approval.ApprovalSubject;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * T8 · 用户积分调账（G-ADJUST）接入审批（方案-第一批审批工作流 T8）。三级 · 资金，修订号 = {@code pts_user_adjustment.version}。
 * 生效 / 驳回走原有复核入口，操作人是「审批服务」。
 */
@Component
public class PointsAdjustmentApprovalSubject implements ApprovalSubject {

    private static final Logger log = LoggerFactory.getLogger(PointsAdjustmentApprovalSubject.class);
    public static final String SUBJECT_TYPE = "shop.points_adjustment";

    private final PointsAdjustmentService adjustments;

    public PointsAdjustmentApprovalSubject(PointsAdjustmentService adjustments) {
        this.adjustments = adjustments;
    }

    @Override public String subjectType() { return SUBJECT_TYPE; }
    @Override public String processCode() { return "shop_points_adjustment"; }
    @Override public String processName() { return "用户积分调账"; }
    @Override public String approverPerm() { return Perms.FINANCE_SETTLE_EXECUTE; }
    @Override public String recheckPerm() { return Perms.FINANCE_PAYOUT_EXECUTE; }
    @Override public Level level() { return Level.FUNDS; }

    @Override
    public Snapshot snapshot(String adjustmentNo) {
        View v = find(adjustmentNo);
        String sign = v.direction() == null ? "" : v.direction().name();
        return new Snapshot("积分调账 · " + v.userNo() + " " + sign + " " + v.points(), "积分类型 " + v.typeCode() + " · 单号 " + adjustmentNo,
                Set.of(), Map.of("typeCode", v.typeCode(), "points", v.points()),
                List.of(Fact.of("用户", v.userNo()),
                        Fact.of("方向", sign),
                        Fact.of("积分", String.valueOf(v.points())),
                        Fact.of("积分类型", v.typeCode()),
                        Fact.of("原因", v.reason()),
                        Fact.of("提交人", v.submittedBy())));
    }

    @Override
    public void apply(String adjustmentNo, long revision) {
        View v = find(adjustmentNo);
        if ("APPROVED".equals(v.state())) return;
        if (!"SUBMITTED".equals(v.state()) || v.version() != revision) {
            throw new ApprovalApplyException(ApprovalApplyException.STALE, "调账单已变化：state=" + v.state() + " version=" + v.version() + "≠" + revision);
        }
        try {
            PayApprovalSupport.asApprovalService(() -> PointsOpsErrors.call(() -> adjustments.review(
                    new Review("APV:" + adjustmentNo + ":" + revision + ":A", adjustmentNo, v.typeCode(), revision, true, "审批通过"),
                    PayApprovalSupport.ACTOR)));
        } catch (RuntimeException e) {
            throw PayApprovalSupport.failure(e);
        }
    }

    @Override
    public void onClosed(String adjustmentNo, long revision, Outcome outcome, String reason) {
        View v = find(adjustmentNo);
        if (!"SUBMITTED".equals(v.state()) || v.version() != revision) return;
        String why = reason == null || reason.isBlank() ? "审批未通过（" + outcome + "）" : reason;
        try {
            PayApprovalSupport.asApprovalService(() -> PointsOpsErrors.call(() -> adjustments.review(
                    new Review("APV:" + adjustmentNo + ":" + revision + ":R", adjustmentNo, v.typeCode(), revision, false, why),
                    PayApprovalSupport.ACTOR)));
        } catch (RuntimeException e) {
            log.warn("[approval] 调账单 {} 驳回回写失败：{}", adjustmentNo, e.toString());
        }
    }

    View find(String adjustmentNo) {
        return adjustments.adjustment(adjustmentNo).orElseThrow(() -> new ApprovalApplyException("NOT_FOUND", "调账单不存在：" + adjustmentNo));
    }
}
