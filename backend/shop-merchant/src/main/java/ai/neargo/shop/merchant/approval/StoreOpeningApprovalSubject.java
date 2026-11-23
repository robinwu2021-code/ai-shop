package ai.neargo.shop.merchant.approval;

import ai.neargo.common.data.scope.DataScopeContext;
import ai.neargo.shop.auth.Perms;
import ai.neargo.shop.merchant.service.StoreOperationalReviewService;
import ai.neargo.shop.spi.approval.ApprovalApplyException;
import ai.neargo.shop.spi.approval.ApprovalSubject;
import java.util.Map;
import java.util.Set;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * 试点：门店经营开通接入审批（TDD-OpsKit审批对接 §1.4）。
 *
 * <p>修订号用 {@code mch_store.version}（提交那一刻的值）：送审之后门店任何改动都会让它变，执行时据此判 STALE。
 * 生效 = 原 {@code decide(no, true)}；驳回 / 撤回 / 放弃 = 原 {@code decide(no, false)}，门店回到「可重新提交」。
 * 两个方向都只在门店仍处于「待审、且修订未变」时动手，所以重复回调、迟到回调都安全。
 */
@Component
public class StoreOpeningApprovalSubject implements ApprovalSubject {

    public static final String SUBJECT_TYPE = "shop.store_opening";

    private final JdbcTemplate db;
    private final StoreOperationalReviewService review;

    public StoreOpeningApprovalSubject(JdbcTemplate db, StoreOperationalReviewService review) {
        this.db = db;
        this.review = review;
    }

    @Override
    public String subjectType() {
        return SUBJECT_TYPE;
    }

    @Override
    public String processCode() {
        return "shop_store_opening";
    }

    @Override
    public String processName() {
        return "门店经营开通";
    }

    @Override
    public String approverPerm() {
        return Perms.MERCHANT_APPLY_AUDIT;
    }

    @Override
    public Snapshot snapshot(String storeNo) {
        var s = state(storeNo);
        return new Snapshot("门店经营开通 · " + s.name(), "门店 " + storeNo + " · 主体 " + s.entityNo(),
                Set.of("merchant:" + s.entityNo()), Map.of("storeNo", storeNo, "entityNo", s.entityNo()));
    }

    @Override
    public void apply(String storeNo, long revision) {
        var s = state(storeNo);
        if ("APPROVED".equals(s.review())) {
            return; // 已生效（本次重投，或套餐履约先一步开通了）
        }
        if (!s.pending() || s.version() != revision) {
            throw new ApprovalApplyException(ApprovalApplyException.STALE,
                    "门店已变化：status=" + s.status() + " review=" + s.review() + " version=" + s.version() + "≠" + revision);
        }
        // 回调线程没有登录人与数据域：审批结论就是授权依据，所以绕开数据域执行（同套餐履约开通）
        DataScopeContext.executeWithoutScope(() -> review.decide(storeNo, true, "审批通过"));
    }

    @Override
    public void onClosed(String storeNo, long revision, Outcome outcome, String reason) {
        var s = state(storeNo);
        if (!s.pending() || s.version() != revision) {
            return; // 门店已变化，不回退
        }
        String why = reason == null || reason.isBlank() ? "审批" + label(outcome) : reason;
        DataScopeContext.executeWithoutScope(() -> review.decide(storeNo, false, why));
    }

    private static String label(Outcome o) {
        return switch (o) {
            case REJECTED -> "驳回";
            case WITHDRAWN -> "撤回";
            case CANCELLED -> "放弃";
        };
    }

    private State state(String storeNo) {
        return db.query("SELECT name,entity_no,status,operational_review,version FROM mch_store WHERE tenant_no='MAIN' AND store_no=? AND deleted=0",
                        (r, i) -> new State(r.getString(1), r.getString(2), r.getString(3), r.getString(4), r.getLong(5)), storeNo)
                .stream().findFirst()
                .orElseThrow(() -> new ApprovalApplyException("NOT_FOUND", "门店不存在：" + storeNo));
    }

    private record State(String name, String entityNo, String status, String review, long version) {
        boolean pending() {
            return "PENDING_OPEN".equals(status) && "PENDING".equals(review);
        }
    }
}
