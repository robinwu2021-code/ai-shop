package ai.neargo.shop.merchant.approval;

import ai.neargo.common.data.scope.DataScopeContext;
import ai.neargo.shop.auth.Perms;
import ai.neargo.shop.merchant.service.MerchantGovernService;
import ai.neargo.shop.spi.approval.ApprovalApplyException;
import ai.neargo.shop.spi.approval.ApprovalSubject;
import ai.neargo.shop.spi.platform.AuditLogPort;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * T24 · 店招 / 公告人工审核接入审批（方案-第二批审批工作流 T24）。一级 · 一般：审核 {@code store:page:audit}。
 *
 * <p>只有机审命中敏感词的公告才进人工审核，进的那一刻送审；对象号 = 审核单号，修订号 = {@code mch_store_audit.version}。
 * 生效 = 原「通过」（内容这时才写到门店），审核人记 OPSKIT；未通过 = 原「驳回」，原因原样回给商家。
 * 两个方向都只在审核单仍待审、修订号没变时动手。
 */
@Component
public class StoreSignApprovalSubject implements ApprovalSubject {

    static final String ACTOR = "OPSKIT";

    private final JdbcTemplate db;
    private final MerchantGovernService govern;
    private final AuditLogPort audit;

    public StoreSignApprovalSubject(JdbcTemplate db, MerchantGovernService govern, AuditLogPort audit) {
        this.db = db;
        this.govern = govern;
        this.audit = audit;
    }

    @Override public String subjectType() { return MerchantGovernService.STORE_SIGN_APPROVAL; }
    @Override public String processCode() { return "shop_store_sign"; }
    @Override public String processName() { return "店招 / 公告审核"; }
    @Override public String approverPerm() { return Perms.STORE_PAGE_AUDIT; }
    @Override public Level level() { return Level.GENERAL; }

    @Override
    public Snapshot snapshot(String auditNo) {
        var a = row(auditNo);
        var store = DataScopeContext.executeWithoutScope(() -> db.queryForList(
                "SELECT name,announcement FROM mch_store WHERE store_no=? AND deleted=0 LIMIT 1", a.get("store_no")));
        String storeName = store.isEmpty() ? String.valueOf(a.get("store_no")) : String.valueOf(store.getFirst().get("name"));
        String before = store.isEmpty() || store.getFirst().get("announcement") == null ? "（无）" : store.getFirst().get("announcement").toString();
        Object until = a.get("notice_until");
        return new Snapshot("店铺公告审核 · " + storeName, "命中：" + nz(a.get("hits")),
                a.get("store_no") == null ? Set.of("merchant:" + a.get("entity_no")) : Set.of("merchant:" + a.get("entity_no"), "store:" + a.get("store_no")),
                Map.of("entityNo", String.valueOf(a.get("entity_no"))),
                List.of(Fact.of("门店", storeName + "（" + a.get("store_no") + "）"),
                        Fact.change("店铺公告", before, nz(a.get("content"))),
                        Fact.of("机审命中", nz(a.get("hits"))),
                        Fact.of("有效期至", until instanceof Number n ? Instant.ofEpochMilli(n.longValue()).toString() : "长期")));
    }

    @Override
    public void apply(String auditNo, long revision) {
        var a = row(auditNo);
        if ("PASSED".equals(a.get("status"))) return; // 重投
        if (!pending(a, revision)) {
            throw new ApprovalApplyException(ApprovalApplyException.STALE, "审核单已变化：status=" + a.get("status"));
        }
        DataScopeContext.executeWithoutScope(() -> govern.decideStoreAudit(auditNo, true, null, ACTOR));
        audit.record("STORE_CONTENT_DECIDE", auditNo, "审批通过");
    }

    @Override
    public void onClosed(String auditNo, long revision, Outcome outcome, String reason) {
        var a = row(auditNo);
        if (!pending(a, revision)) return;
        String why = reason == null || reason.isBlank()
                ? switch (outcome) { case REJECTED -> "审批未通过"; case WITHDRAWN -> "审批已撤回"; case CANCELLED -> "审批已作废"; }
                : reason;
        DataScopeContext.executeWithoutScope(() -> govern.decideStoreAudit(auditNo, false, why, ACTOR));
        audit.record("STORE_CONTENT_DECIDE", auditNo, "驳回：" + why);
    }

    private static boolean pending(Map<String, Object> a, long revision) {
        return "PENDING".equals(a.get("status")) && a.get("version") instanceof Number v && v.longValue() == revision;
    }

    private Map<String, Object> row(String auditNo) {
        var rows = DataScopeContext.executeWithoutScope(() -> db.queryForList(
                "SELECT entity_no,store_no,content,hits,notice_until,status,version FROM mch_store_audit WHERE audit_no=? AND deleted=0", auditNo));
        if (rows.isEmpty()) throw new ApprovalApplyException("NOT_FOUND", "审核单不存在：" + auditNo);
        return rows.getFirst();
    }

    private static String nz(Object v) {
        return v == null ? "—" : v.toString();
    }
}
