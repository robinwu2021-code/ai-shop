package ai.neargo.shop.promotion.approval;

import ai.neargo.common.data.scope.DataScopeContext;
import ai.neargo.shop.auth.Perms;
import ai.neargo.shop.auth.SecurityUtils;
import ai.neargo.shop.common.BizException;
import ai.neargo.shop.promotion.service.PlatformActivityService;
import ai.neargo.shop.spi.approval.ApprovalApplyException;
import ai.neargo.shop.spi.approval.ApprovalPort;
import ai.neargo.shop.spi.approval.ApprovalSubject;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.zip.CRC32;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Lazy;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * T18 · 平台活动报名接入审批（方案-第二批审批工作流 T18 / Q12）。二级 · 重要：审核 {@code marketing:campaign:update}；
 * 平台补贴上限 ≥ 阈值（默认 10 万元，送审字段 {@code platformMaxMinor} 也可在「审批流程」页做条件）时加复核。
 *
 * <p>商家报名（含驳回后重报）即送审。报名的状态更新不涨 {@code version}，所以修订号 =
 * version、状态、份数、补贴上限、商品清单的 CRC32；重报时 version 手动 +1，免得内容相同的重报撞上一次的修订号。
 * 生效 = 原「通过」（带条件占平台预算，超了 → 生效失败 ENROLLMENT_OVER_BUDGET），审核人记 OPSKIT；未通过 = 原「驳回」。
 */
@Component
public class EnrollmentApprovalSubject implements ApprovalSubject {

    static final String ACTOR = "OPSKIT";

    private final JdbcTemplate db;
    private final PlatformActivityService activities;
    private final ObjectProvider<ApprovalPort> approvals;
    /** 商家名在平台基础库，不跨库连表：经商户端口取 */
    private final ObjectProvider<ai.neargo.shop.spi.user.MerchantQueryPort> merchants;
    private final long recheckMinor;

    public EnrollmentApprovalSubject(JdbcTemplate db, @Lazy PlatformActivityService activities, ObjectProvider<ApprovalPort> approvals,
                                     ObjectProvider<ai.neargo.shop.spi.user.MerchantQueryPort> merchants,
                                     @Value("${shop.approval.enrollment-recheck-minor:10000000}") long recheckMinor) {
        this.db = db;
        this.activities = activities;
        this.approvals = approvals;
        this.merchants = merchants;
        this.recheckMinor = recheckMinor;
    }

    @Override public String subjectType() { return PlatformActivityService.ENROLLMENT_APPROVAL; }
    @Override public String processCode() { return "shop_promotion_enrollment"; }
    @Override public String processName() { return "平台活动报名"; }
    @Override public String approverPerm() { return Perms.MARKETING_CAMPAIGN_UPDATE; }
    @Override public String recheckPerm() { return Perms.MARKETING_CAMPAIGN_UPDATE; }
    @Override public Level level() { return Level.IMPORTANT; }

    public boolean enabled() {
        ApprovalPort port = approvals.getIfAvailable();
        return port != null && port.enabled(subjectType());
    }

    /** 报名（重报）事务内调用：按当前内容算修订号送审。 */
    public void submit(String enrollmentNo) {
        ApprovalPort port = approvals.getIfAvailable();
        if (port == null || !port.enabled(subjectType())) return;
        var user = SecurityUtils.currentUser().orElse(null);
        port.submit(subjectType(), enrollmentNo, revision(row(enrollmentNo), goods(enrollmentNo)),
                new ApprovalPort.Submitter(user == null ? "SYSTEM" : user.userNo(), user == null ? "商家" : user.nickname()));
    }

    @Override
    public Snapshot snapshot(String enrollmentNo) {
        var e = row(enrollmentNo);
        var goods = goods(enrollmentNo);
        long need = num(e.get("platform_max_minor"));
        long reserved = num(e.get("enroll_reserved_minor"));
        Object budget = e.get("budget_minor");
        Map<String, Object> fields = new HashMap<>();
        fields.put("platformMaxMinor", need);
        fields.put(RECHECK_FIELD, need >= recheckMinor);
        return new Snapshot("平台活动报名 · " + nz(e.get("activity_name")), nz(e.get("entity_name")) + " · " + e.get("quota") + " 份",
                Set.of("merchant:" + e.get("entity_no")), fields,
                List.of(Fact.of("活动", nz(e.get("activity_name")) + "（" + e.get("activity_no") + "）"),
                        Fact.of("商家", nz(e.get("entity_name")) + "（" + e.get("entity_no") + "）"),
                        Fact.of("报名份数", String.valueOf(e.get("quota"))),
                        Fact.of("平台补贴上限", yuan(need)),
                        Fact.of("商家承担上限", yuan(num(e.get("merchant_max_minor")))),
                        Fact.change("活动已占预算", yuan(reserved), yuan(reserved + need)),
                        Fact.of("活动预算", budget == null ? "不限" : yuan(num(budget))),
                        Fact.of("报名商品", goods.size() + " 个：" + String.join("、", goods.subList(0, Math.min(10, goods.size()))))));
    }

    @Override
    public void apply(String enrollmentNo, long revision) {
        var e = row(enrollmentNo);
        if ("APPROVED".equals(e.get("status"))) return; // 重投
        if (!"SUBMITTED".equals(e.get("status")) || revision(e, goods(enrollmentNo)) != revision) {
            throw new ApprovalApplyException(ApprovalApplyException.STALE, "报名已变化：status=" + e.get("status"));
        }
        try {
            DataScopeContext.executeWithoutScope(() -> activities.review(enrollmentNo, true, null, ACTOR));
        } catch (BizException b) {
            throw new ApprovalApplyException(b.errorCode().name(), b.getMessage() == null ? b.errorCode().name() : b.getMessage());
        }
    }

    @Override
    public void onClosed(String enrollmentNo, long revision, Outcome outcome, String reason) {
        var e = row(enrollmentNo);
        if (!"SUBMITTED".equals(e.get("status")) || revision(e, goods(enrollmentNo)) != revision) return;
        String why = reason == null || reason.isBlank()
                ? switch (outcome) { case REJECTED -> "审批未通过"; case WITHDRAWN -> "审批已撤回"; case CANCELLED -> "审批已作废"; }
                : reason;
        DataScopeContext.executeWithoutScope(() -> activities.review(enrollmentNo, false, why, ACTOR));
    }

    static long revision(Map<String, Object> e, List<String> goods) {
        String s = e.get("version") + "|" + e.get("status") + "|" + e.get("quota") + "|" + e.get("platform_max_minor")
                + "|" + e.get("merchant_max_minor") + "|" + String.join(",", goods);
        var crc = new CRC32();
        crc.update(s.getBytes(StandardCharsets.UTF_8));
        return crc.getValue();
    }

    private Map<String, Object> row(String enrollmentNo) {
        var rows = DataScopeContext.executeWithoutScope(() -> db.queryForList(
                "SELECT e.enrollment_no,e.activity_no,e.entity_no,e.quota,e.platform_max_minor,e.merchant_max_minor,e.status,e.version,"
                        + " a.name AS activity_name,a.budget_minor,a.enroll_reserved_minor"
                        + " FROM pmt_enrollment e LEFT JOIN pmt_activity a ON a.activity_no=e.activity_no AND a.deleted=0"
                        + " WHERE e.enrollment_no=? AND e.deleted=0", enrollmentNo));
        if (rows.isEmpty()) throw new ApprovalApplyException("NOT_FOUND", "报名不存在：" + enrollmentNo);
        var row = new java.util.LinkedHashMap<>(rows.getFirst());
        var port = merchants.getIfAvailable();
        Object entityNo = row.get("entity_no");
        row.put("entity_name", port == null || entityNo == null ? null
                : port.find(entityNo.toString()).map(ai.neargo.shop.spi.user.MerchantQueryPort.MerchantBrief::merchantName).orElse(null));
        return row;
    }

    private List<String> goods(String enrollmentNo) {
        return DataScopeContext.executeWithoutScope(() -> db.queryForList(
                "SELECT goods_no FROM pmt_enrollment_goods WHERE enrollment_no=? AND deleted=0 ORDER BY goods_no", String.class, enrollmentNo));
    }

    private static long num(Object v) {
        return v instanceof Number n ? n.longValue() : 0;
    }

    private static String yuan(long minor) {
        return BigDecimal.valueOf(minor, 2).toPlainString() + " 元";
    }

    private static String nz(Object v) {
        return v == null ? "—" : v.toString();
    }
}
