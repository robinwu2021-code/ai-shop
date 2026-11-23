package ai.neargo.shop.commercebridge.approval;

import ai.neargo.commerce.starter.http.OpsApprovalGatePort;
import java.util.ArrayList;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/** T9b · 商城线下付款核款（审核 {@code commerce:mall:offline:review} → 复核 {@code commerce:mall:offline:confirm}）。拟定结果 = 「确认收款」请求体。对象号末段为确认动作号。 */
@Component
public class MallOfflinePaymentApprovalSubject extends MallGateSubject {

    public static final String SUBJECT_TYPE = "shop.mall_offline_payment";

    public MallOfflinePaymentApprovalSubject(JdbcTemplate db) {
        super(db);
    }

    @Override public String subjectType() { return SUBJECT_TYPE; }
    @Override public String processCode() { return "shop_mall_offline_payment"; }
    @Override public String processName() { return "商城线下付款核款"; }
    @Override public String approverPerm() { return "commerce:mall:offline:review"; }
    @Override public String recheckPerm() { return "commerce:mall:offline:confirm"; }
    @Override public Level level() { return Level.FUNDS; }
    @Override String action() { return OpsApprovalGatePort.MALL_OFFLINE_CONFIRM; }

    @Override
    String state(Ref r) {
        var order = db.queryForList("SELECT CONCAT(version,':',pay_status) FROM mall_order WHERE tenant_no=? AND store_no=? AND order_no=?",
                String.class, r.tenant(), r.store(), r.order());
        if (order.isEmpty()) return null;
        var claims = db.queryForList("SELECT claim_no,claim_snapshot FROM mall_offline_claim WHERE tenant_no=? AND order_no=? ORDER BY claim_no",
                r.tenant(), r.order());
        var sb = new StringBuilder(order.getFirst());
        claims.forEach(c -> sb.append('|').append(c.get("claim_no")).append('=').append(c.get("claim_snapshot")));
        return sb.toString();
    }

    @Override
    boolean awaiting(Ref r) {
        Integer n = db.queryForObject("SELECT COUNT(*) FROM mall_offline_action WHERE tenant_no=? AND order_no=? AND action_no=?",
                Integer.class, r.tenant(), r.order(), r.subject());
        return n != null && n == 0;
    }

    @Override
    public Snapshot snapshot(String ref, Map<String, Object> p) {
        var r = Ref.parse(ref);
        var facts = new ArrayList<Fact>();
        facts.add(Fact.of("订单", r.order()));
        if (p != null && !p.isEmpty()) {
            facts.add(Fact.of("凭证号", text(p.get("claimNo"))));
            facts.add(Fact.of("核定实收", yuan(p.get("actualAmount"))));
            facts.add(Fact.of("到账时间", text(p.get("actualPaidAt"))));
            facts.add(Fact.of("收款人", text(p.get("actualReceiver"))));
            facts.add(Fact.of("银行流水号", text(p.get("bankReference"))));
            if (p.get("collectorNo") != null) facts.add(Fact.of("代收人 / 收款单号", p.get("collectorNo") + " / " + text(p.get("collectionReference"))));
            facts.add(Fact.of("确认原因", text(p.get("reason"))));
        }
        facts.add(Fact.of("执行方式", "审批通过后，由运营在核款页再点「确认收款」执行"));
        return new Snapshot("商城线下核款 · " + r.order(), "门店 " + r.store() + " · 动作 " + r.subject(),
                scopes(r), Map.of("storeNo", r.store(), "orderNo", r.order(), "actionNo", r.subject()), facts);
    }
}
