package ai.neargo.shop.paybridge.approval;

import ai.neargo.shop.auth.Perms;
import ai.neargo.shop.pay.points.admin.PointsTreasuryService;
import ai.neargo.shop.pay.points.admin.PointsTreasuryService.RequestView;
import ai.neargo.shop.pay.points.admin.PointsTreasuryService.Review;
import ai.neargo.shop.payclient.PointsTreasuryAppService;
import ai.neargo.shop.spi.approval.ApprovalApplyException;
import ai.neargo.shop.spi.approval.ApprovalSubject;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * T8 · 积分资金池申请（备款 / 划转 / 提取，含补充计划自动生成的申请）接入审批（方案-第一批审批工作流 T8）。
 *
 * <p>三级 · 资金：审核 {@code finance:settle:execute} → 复核 {@code finance:payout:execute}。修订号 = {@code pts_pool_request.version}。
 * 生效 / 驳回都走原有的复核入口（{@code review}，同一套版本、状态、过账校验与操作日志），操作人是「审批服务」——
 * 它永远不是提交人，原来的「不得自批」校验自然不拦（用户 2026-11-23 定：一律可以本人审批，审批人记在 OpsKit）。
 */
@Component
public class PointsPoolRequestApprovalSubject implements ApprovalSubject {

    private static final Logger log = LoggerFactory.getLogger(PointsPoolRequestApprovalSubject.class);
    public static final String SUBJECT_TYPE = "shop.points_pool_request";

    private final PointsTreasuryService treasury;
    private final PointsTreasuryAppService app;

    public PointsPoolRequestApprovalSubject(PointsTreasuryService treasury, PointsTreasuryAppService app) {
        this.treasury = treasury;
        this.app = app;
    }

    @Override public String subjectType() { return SUBJECT_TYPE; }
    @Override public String processCode() { return "shop_points_pool_request"; }
    @Override public String processName() { return "积分资金池申请"; }
    @Override public String approverPerm() { return Perms.FINANCE_SETTLE_EXECUTE; }
    @Override public String recheckPerm() { return Perms.FINANCE_PAYOUT_EXECUTE; }
    @Override public Level level() { return Level.FUNDS; }

    @Override
    public Snapshot snapshot(String requestNo) {
        RequestView r = find(requestNo);
        return new Snapshot("积分资金池 · " + r.action() + " " + PayApprovalSupport.money(r.amountMinor()),
                "积分类型 " + r.typeCode() + " · 申请 " + requestNo, Set.of(),
                Map.of("typeCode", r.typeCode(), "action", r.action().name(), "amountMinor", r.amountMinor()),
                List.of(Fact.of("动作", r.action().name()),
                        Fact.of("积分类型", r.typeCode()),
                        Fact.of("金额", PayApprovalSupport.money(r.amountMinor())),
                        Fact.of("资金证据", r.hasEvidence() ? "已附" : "无"),
                        Fact.of("提交原因", r.submittedReason() == null ? "—" : r.submittedReason()),
                        Fact.of("提交人", r.submittedBy())));
    }

    @Override
    public void apply(String requestNo, long revision) {
        RequestView r = find(requestNo);
        if ("APPROVED".equals(r.state())) return; // 重投
        if (!"SUBMITTED".equals(r.state()) || r.version() != revision) {
            throw new ApprovalApplyException(ApprovalApplyException.STALE, "申请已变化：state=" + r.state() + " version=" + r.version() + "≠" + revision);
        }
        try {
            PayApprovalSupport.asApprovalService(() -> app.review(
                    new Review("APV:" + requestNo + ":" + revision + ":A", r.typeCode(), requestNo, revision, true, "审批通过"),
                    PayApprovalSupport.ACTOR));
        } catch (RuntimeException e) {
            throw PayApprovalSupport.failure(e);
        }
    }

    @Override
    public void onClosed(String requestNo, long revision, Outcome outcome, String reason) {
        RequestView r = find(requestNo);
        if (!"SUBMITTED".equals(r.state()) || r.version() != revision) return;
        String why = reason == null || reason.isBlank() ? "审批未通过（" + outcome + "）" : reason;
        try {
            PayApprovalSupport.asApprovalService(() -> app.review(
                    new Review("APV:" + requestNo + ":" + revision + ":R", r.typeCode(), requestNo, revision, false, why),
                    PayApprovalSupport.ACTOR));
        } catch (RuntimeException e) {
            log.warn("[approval] 资金池申请 {} 驳回回写失败：{}", requestNo, e.toString());
        }
    }

    RequestView find(String requestNo) {
        return treasury.request(requestNo).orElseThrow(() -> new ApprovalApplyException("NOT_FOUND", "资金池申请不存在：" + requestNo));
    }
}
