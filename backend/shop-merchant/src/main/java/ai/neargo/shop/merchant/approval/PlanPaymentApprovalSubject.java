package ai.neargo.shop.merchant.approval;

import ai.neargo.commerce.api.common.Money;
import ai.neargo.common.data.scope.DataScopeContext;
import ai.neargo.shop.auth.LoginUser;
import ai.neargo.shop.common.BizException;
import ai.neargo.shop.merchant.plan.EntityPlanPurchaseService;
import ai.neargo.shop.merchant.plan.EntityPlanPurchaseService.Actor;
import ai.neargo.shop.merchant.plan.EntityPlanPurchaseService.ActionView;
import ai.neargo.shop.merchant.plan.EntityPlanPurchaseService.Confirm;
import ai.neargo.shop.merchant.plan.EntityPlanPurchaseService.OrderView;
import ai.neargo.shop.spi.approval.ApprovalApplyException;
import ai.neargo.shop.spi.approval.ApprovalPort;
import ai.neargo.shop.spi.approval.ApprovalSubject;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;
import java.util.zip.CRC32;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * T9c · 套餐线下付款确认接入审批（方案-第一批审批工作流 T9c / Q7）。三级 · 动钱：审核 {@code offline:review} → 复核 {@code offline:confirm}。
 *
 * <p>运营在付款确认页照旧填「确认收款」（实收、到账时间、流水号、本方凭证、原因）—— 启用审批后这一步<b>只送审</b>，
 * 所填内容作为拟定结果冻结在审批单上；审批通过才按原样执行 {@code confirm}。
 * 对象号 {@code entityNo:orderNo}；订单没有版本列，修订号 = 订单状态 + 各凭证状态 + 各确认动作状态的 CRC32，任一变了即 STALE。
 * 执行时以<b>填单人</b>身份调用（凭证归属与幂等都按填单人校验），绕开数据域；谁批的记在 OpsKit 审批单上。
 * 未通过只记日志：不同意的是这份拟定结果，订单仍待确认，运营可改了再送或在页面上直接退回凭证（同 T10）。
 */
@Slf4j
@Component
@ConditionalOnProperty(
    name = {"commerce.enabled", "commerce.mall.http.enabled", "commerce.mall.enabled", "commerce.mall.payment.enabled", "commerce.mall.offline.enabled"},
    havingValue = "true")
public class PlanPaymentApprovalSubject implements ApprovalSubject {

    public static final String SUBJECT_TYPE = "shop.plan_offline_payment";
    static final String ACTOR = "OPSKIT";

    private final EntityPlanPurchaseService plans;
    private final ObjectProvider<ApprovalPort> approvals;

    public PlanPaymentApprovalSubject(EntityPlanPurchaseService plans, ObjectProvider<ApprovalPort> approvals) {
        this.plans = plans;
        this.approvals = approvals;
    }

    @Override public String subjectType() { return SUBJECT_TYPE; }
    @Override public String processCode() { return "shop_plan_offline_payment"; }
    @Override public String processName() { return "套餐线下付款确认"; }
    @Override public String approverPerm() { return "commerce:mall:offline:review"; }
    @Override public String recheckPerm() { return "commerce:mall:offline:confirm"; }
    @Override public Level level() { return Level.FUNDS; }

    public static String ref(String entityNo, String orderNo) {
        return entityNo + ":" + orderNo;
    }

    public boolean enabled() {
        ApprovalPort port = approvals.getIfAvailable();
        return port != null && port.enabled(SUBJECT_TYPE);
    }

    /** 确认页「确认收款」在启用审批后改为送审：修订号取当前订单状态，拟定结果 = 所填确认内容 + 填单人。 */
    @Transactional
    public ActionView submit(String entityNo, String orderNo, Confirm c, String operatorNo, String operatorName) {
        var order = plans.order(new Actor(operatorNo, entityNo, null, true), orderNo);
        if (order.actions() != null && order.actions().stream().anyMatch(a -> a.actionNo().equals(c.actionNo()))) {
            throw BizException.of(ai.neargo.shop.common.ErrorCode.CONFLICT);
        }
        approvals.getObject().submit(SUBJECT_TYPE, ref(entityNo, orderNo), revision(order),
                new ApprovalPort.Submitter(operatorNo, operatorName), proposal(c, operatorNo));
        return new ActionView(c.actionNo(), "PENDING_APPROVAL", null, c.reason());
    }

    @Override
    public Snapshot snapshot(String ref) {
        return snapshot(ref, Map.of());
    }

    @Override
    public Snapshot snapshot(String ref, Map<String, Object> proposal) {
        String[] r = split(ref);
        var o = read(r[0], r[1]);
        var facts = new ArrayList<Fact>();
        facts.add(Fact.of("商户主体", o.entityNo()));
        facts.add(Fact.of("订单", o.orderNo()));
        if (o.offer() != null) {
            facts.add(Fact.of("套餐", o.offer().name() + "（" + o.offer().planCode() + "）"));
            facts.add(Fact.of("天数 / 配额", o.offer().days() + " 天 · 门店 " + o.offer().storeQuota() + " · 员工 " + o.offer().staffQuota()));
            facts.add(Fact.of("价格", yuan(o.offer().price())));
        }
        if (proposal != null && !proposal.isEmpty()) {
            var c = confirm(proposal);
            var declared = c.claimNo() == null || o.claims() == null ? null
                    : o.claims().stream().filter(x -> c.claimNo().equals(x.claimNo())).findFirst().orElse(null);
            facts.add(declared == null || declared.declaration() == null
                    ? Fact.of("核定实收", yuan(c.amount()))
                    : Fact.change("实收（申报 → 核定）", yuan(declared.declaration().amount()), yuan(c.amount())));
            facts.add(Fact.of("到账时间", c.paidAt().toString()));
            facts.add(Fact.of("银行流水号", c.bankReference()));
            facts.add(Fact.of("确认原因", c.reason()));
            facts.add(Fact.of("填单人", String.valueOf(proposal.get("operatorNo"))));
        }
        facts.add(Fact.change("订单状态", o.status(), "已确认收款"));
        return new Snapshot("套餐线下付款确认 · " + o.orderNo(), "主体 " + o.entityNo() + " · 门店 " + o.storeNo(),
                Set.of("merchant:" + o.entityNo()), Map.of("entityNo", o.entityNo(), "orderNo", o.orderNo()), facts);
    }

    @Override
    public void apply(String ref, long revision) {
        throw new ApprovalApplyException("PROPOSAL_REQUIRED", "套餐付款确认缺少拟定结果");
    }

    @Override
    public void apply(String ref, long revision, Map<String, Object> proposal) {
        if (proposal == null || proposal.isEmpty()) apply(ref, revision);
        String[] r = split(ref);
        var c = confirm(proposal);
        var o = read(r[0], r[1]);
        if (o.actions() != null && o.actions().stream().anyMatch(a -> a.actionNo().equals(c.actionNo()))) {
            return; // 重投：该确认动作已落库（后续推进由原有 advance / recover 负责）
        }
        if (revision(o) != revision) {
            throw new ApprovalApplyException(ApprovalApplyException.STALE, "订单已变化：status=" + o.status());
        }
        String operatorNo = (String) proposal.get("operatorNo");
        try {
            asApprovalService(() -> plans.confirm(new Actor(operatorNo, r[0], null, true), r[1], c));
        } catch (ApprovalApplyException e) {
            throw e;
        } catch (BizException e) {
            throw new ApprovalApplyException(e.errorCode().name(), e.getMessage() == null ? e.errorCode().name() : e.getMessage());
        }
    }

    @Override
    public void onClosed(String ref, long revision, Outcome outcome, String reason) {
        log.info("[approval] 套餐付款确认 {} 版本 {} 审批未通过（{}）：{}", ref, revision, outcome, reason);
    }

    static long revision(OrderView o) {
        var sb = new StringBuilder(String.valueOf(o.status()));
        if (o.claims() != null) o.claims().forEach(c -> sb.append('|').append(c.claimNo()).append(':').append(c.status()));
        if (o.actions() != null) o.actions().forEach(a -> sb.append('|').append(a.actionNo()).append(':').append(a.status()));
        var crc = new CRC32();
        crc.update(sb.toString().getBytes(StandardCharsets.UTF_8));
        return crc.getValue();
    }

    static Map<String, Object> proposal(Confirm c, String operatorNo) {
        var m = new HashMap<String, Object>();
        m.put("operatorNo", operatorNo);
        m.put("actionNo", c.actionNo());
        if (c.claimNo() != null) m.put("claimNo", c.claimNo());
        m.put("currency", c.amount().currency());
        m.put("minor", c.amount().minor());
        m.put("paidAt", c.paidAt().toString());
        m.put("bankReference", c.bankReference());
        m.put("evidenceKey", c.evidenceKey());
        m.put("reason", c.reason());
        return m;
    }

    static Confirm confirm(Map<String, Object> p) {
        return new Confirm((String) p.get("actionNo"), (String) p.get("claimNo"),
                new Money((String) p.get("currency"), ((Number) p.get("minor")).longValue()),
                Instant.parse((String) p.get("paidAt")), (String) p.get("bankReference"),
                (String) p.get("evidenceKey"), (String) p.get("reason"));
    }

    private OrderView read(String entityNo, String orderNo) {
        try {
            return asApprovalService(() -> plans.order(new Actor(ACTOR, entityNo, null, true), orderNo));
        } catch (BizException e) {
            throw new ApprovalApplyException("NOT_FOUND", "套餐订单不存在：" + entityNo + ":" + orderNo);
        }
    }

    private static String[] split(String ref) {
        String[] r = ref.split(":", 2);
        if (r.length != 2) throw new ApprovalApplyException("NOT_FOUND", "对象号不合法：" + ref);
        return r;
    }

    private static String yuan(Money m) {
        return m == null ? "—" : m.currency() + " " + BigDecimal.valueOf(m.minor(), 2).toPlainString();
    }

    private static <T> T asApprovalService(Supplier<T> work) {
        var previous = SecurityContextHolder.getContext();
        try {
            var ctx = SecurityContextHolder.createEmptyContext();
            ctx.setAuthentication(new UsernamePasswordAuthenticationToken(
                    LoginUser.operator(ACTOR, "审批服务", List.of(), List.of()), null, List.of()));
            SecurityContextHolder.setContext(ctx);
            return DataScopeContext.executeWithoutScope(work);
        } finally {
            SecurityContextHolder.setContext(previous);
        }
    }
}
