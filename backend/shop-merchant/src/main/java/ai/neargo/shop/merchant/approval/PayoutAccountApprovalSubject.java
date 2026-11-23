package ai.neargo.shop.merchant.approval;

import ai.neargo.common.data.scope.DataScopeContext;
import ai.neargo.shop.auth.Perms;
import ai.neargo.shop.merchant.service.PayoutAccountService;
import ai.neargo.shop.spi.approval.ApprovalApplyException;
import ai.neargo.shop.spi.approval.ApprovalSubject;
import ai.neargo.shop.spi.platform.AuditLogPort;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * T17 · 自营供应商出款账户接入审批（方案-第二批审批工作流 T17）。二级 · 重要：审核 {@code finance:payout:execute}；
 * 主体已有生效账户（即「换卡」）时加复核。
 *
 * <p>商家提交即送审，对象号 = 账户号，修订号 = {@code mch_payout_account.version}。
 * 生效 = 原「审核通过」（先停同主体旧账户再启用新卡），审核人记为 OPSKIT；
 * 驳回 / 撤回 / 放弃一律置 REJECTED 并把原因回给商家 —— 留在待审的话商家再也提交不了新卡（同主体只能有一张在审）。
 */
@Component
public class PayoutAccountApprovalSubject implements ApprovalSubject {

    static final String ACTOR = "OPSKIT";

    private final JdbcTemplate db;
    private final PayoutAccountService accounts;
    private final AuditLogPort audit;

    public PayoutAccountApprovalSubject(JdbcTemplate db, PayoutAccountService accounts, AuditLogPort audit) {
        this.db = db;
        this.accounts = accounts;
        this.audit = audit;
    }

    @Override public String subjectType() { return PayoutAccountService.APPROVAL_TYPE; }
    @Override public String processCode() { return "shop_payout_account"; }
    @Override public String processName() { return "供应商出款账户"; }
    @Override public String approverPerm() { return Perms.FINANCE_PAYOUT_EXECUTE; }
    @Override public String recheckPerm() { return Perms.FINANCE_PAYOUT_EXECUTE; }
    @Override public Level level() { return Level.IMPORTANT; }

    @Override
    public Snapshot snapshot(String accountNo) {
        var a = row(accountNo);
        var current = DataScopeContext.executeWithoutScope(() -> db.queryForList(
                "SELECT account_masked,bank_name FROM mch_payout_account WHERE entity_no=? AND status='ACTIVE' AND deleted=0 LIMIT 1",
                a.get("entity_no")));
        boolean replacing = !current.isEmpty();
        String before = replacing ? current.getFirst().get("account_masked") + " · " + nz(current.getFirst().get("bank_name")) : "无";
        var facts = new ArrayList<Fact>();
        facts.add(Fact.of("商户主体", nz(a.get("entity_name")) + "（" + a.get("entity_no") + "）"));
        facts.add(Fact.change("收款账户", before, a.get("account_masked") + " · " + nz(a.get("bank_name"))));
        facts.add(Fact.of("户名", nz(a.get("account_name"))));
        facts.add(Fact.of("账户类型", nz(a.get("account_type"))));
        facts.add(Fact.of("开户支行", nz(a.get("bank_branch"))));
        Map<String, Object> fields = new HashMap<>();
        fields.put("entityNo", a.get("entity_no"));
        fields.put(RECHECK_FIELD, replacing);
        return new Snapshot("供应商出款账户 · " + nz(a.get("entity_name")), (replacing ? "换卡" : "首次登记") + " · " + a.get("account_masked"),
                Set.of("merchant:" + a.get("entity_no")), fields, facts);
    }

    @Override
    public void apply(String accountNo, long revision) {
        var a = row(accountNo);
        if ("ACTIVE".equals(a.get("status"))) return; // 重投
        if (!accounts.auditApproved(accountNo, revision, true, "审批通过", ACTOR)) {
            throw new ApprovalApplyException(ApprovalApplyException.STALE, "账户已变化：status=" + a.get("status"));
        }
        audit.record("PAYOUT_ACCOUNT_AUDIT", accountNo, "审批通过 收款账户 %s（%s）".formatted(a.get("account_masked"), a.get("entity_no")), true);
    }

    @Override
    public void onClosed(String accountNo, long revision, Outcome outcome, String reason) {
        String why = reason == null || reason.isBlank()
                ? switch (outcome) { case REJECTED -> "审批未通过"; case WITHDRAWN -> "审批已撤回"; case CANCELLED -> "审批已作废"; }
                : reason;
        if (accounts.auditApproved(accountNo, revision, false, why, ACTOR)) {
            audit.record("PAYOUT_ACCOUNT_AUDIT", accountNo, "审批未通过：" + why, true);
        }
    }

    private Map<String, Object> row(String accountNo) {
        List<Map<String, Object>> rows = DataScopeContext.executeWithoutScope(() -> db.queryForList(
                "SELECT a.entity_no,a.account_masked,a.account_name,a.account_type,a.bank_name,a.bank_branch,a.status,e.name AS entity_name"
                        + " FROM mch_payout_account a LEFT JOIN mch_entity e ON e.entity_no=a.entity_no AND e.deleted=0"
                        + " WHERE a.account_no=? AND a.deleted=0", accountNo));
        if (rows.isEmpty()) throw new ApprovalApplyException("NOT_FOUND", "出款账户不存在：" + accountNo);
        return rows.getFirst();
    }

    private static String nz(Object v) {
        return v == null ? "—" : v.toString();
    }
}
