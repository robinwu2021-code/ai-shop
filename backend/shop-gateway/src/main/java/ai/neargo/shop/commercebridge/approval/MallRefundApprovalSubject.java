package ai.neargo.shop.commercebridge.approval;

import ai.neargo.commerce.starter.http.OpsApprovalGatePort;
import java.util.ArrayList;
import java.util.Map;
import org.springframework.stereotype.Component;

/** T9a · 商城退款（审核 {@code commerce:refund:review} → 复核 {@code commerce:mall:refund:approve}）。拟定结果 = 「退款通过」请求体（核价单号、退现金额、恢复额度）。 */
@Component
public class MallRefundApprovalSubject extends MallGateSubject {

    public static final String SUBJECT_TYPE = "shop.mall_refund";

    public MallRefundApprovalSubject(Databases databases) {
        super(databases.mall, databases.storeEntity, databases.split);
    }

    @Override public String subjectType() { return SUBJECT_TYPE; }
    @Override public String processCode() { return "shop_mall_refund"; }
    @Override public String processName() { return "商城退款"; }
    @Override public String approverPerm() { return "commerce:refund:review"; }
    @Override public String recheckPerm() { return "commerce:mall:refund:approve"; }
    @Override public Level level() { return Level.FUNDS; }
    @Override String action() { return OpsApprovalGatePort.MALL_REFUND_APPROVE; }

    private Map<String, Object> row(Ref r) {
        var rows = db.queryForList("SELECT version,status,reason,requested_cash_minor FROM mall_refund"
                + " WHERE tenant_no=? AND store_no=? AND order_no=? AND refund_no=?"
                + (split ? " AND JSON_CONTAINS_PATH(policy_snapshot,'one','$.refundNo')" : ""), r.tenant(), r.store(), r.order(), r.subject());
        return rows.isEmpty() ? null : rows.getFirst();
    }

    @Override
    String state(Ref r) {
        var row = row(r);
        return row == null ? null : row.get("version") + ":" + row.get("status");
    }

    @Override
    boolean awaiting(Ref r) {
        var row = row(r);
        return row != null && "REQUESTED".equals(row.get("status"));
    }

    @Override
    public Snapshot snapshot(String ref, Map<String, Object> p) {
        var r = Ref.parse(ref);
        var row = row(r);
        var facts = new ArrayList<Fact>();
        facts.add(Fact.of("订单 / 退款单", r.order() + " / " + r.subject()));
        if (row != null) {
            facts.add(Fact.of("退款原因", text(row.get("reason"))));
            facts.add(Fact.of("申请退现（最小单位）", text(row.get("requested_cash_minor"))));
        }
        if (p != null && !p.isEmpty()) {
            facts.add(Fact.of("核价单", text(p.get("reviewQuoteNo"))));
            facts.add(Fact.of("拟退现金", yuan(p.get("cashAmount"))));
            facts.add(Fact.of("拟恢复额度", yuan(p.get("restoreCredit"))));
            facts.add(Fact.of("审核说明", text(p.get("reviewEvidence"))));
        }
        facts.add(Fact.of("执行方式", "审批通过后，由运营在订单页再点「退款通过」执行"));
        return new Snapshot("商城退款 · " + r.subject(), "门店 " + r.store() + " · 订单 " + r.order(),
                scopes(r), Map.of("storeNo", r.store(), "orderNo", r.order(), "refundNo", r.subject()), facts);
    }
}
