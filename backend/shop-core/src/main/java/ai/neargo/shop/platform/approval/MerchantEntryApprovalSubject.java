package ai.neargo.shop.platform.approval;

import ai.neargo.common.data.scope.DataScopeContext;
import ai.neargo.shop.auth.LoginUser;
import ai.neargo.shop.auth.Perms;
import ai.neargo.shop.platform.OpsService;
import ai.neargo.shop.platform.entity.MchEntityApply;
import ai.neargo.shop.platform.mapper.PlatformMappers.MerchantApplyMapper;
import ai.neargo.shop.spi.approval.ApprovalApplyException;
import ai.neargo.shop.spi.approval.ApprovalSubject;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Lazy;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

/**
 * T10 · 入驻申请审核接入审批（方案-第一批审批工作流 T10）。
 *
 * <p>送审：运营在入驻详情选好服务范围 / 社区 / 授权码点「通过」→ {@code OpsService.auditApply} 带拟定结果送审（Q7）；驳回仍当场生效。
 * 生效：{@code OpsService.applyApprovedEntry} 用冻结的拟定结果建主体、授码、转存资质；申请单在审批期间变了 → STALE。
 * 审批驳回 / 撤回 / 放弃：申请单留在 REVIEWING，运营可改拟定结果重新送审，或直接驳回申请。
 * 修订号 = {@code mch_entity_apply.version}。
 */
@Component
public class MerchantEntryApprovalSubject implements ApprovalSubject {

    private static final Logger log = LoggerFactory.getLogger(MerchantEntryApprovalSubject.class);
    public static final String SUBJECT_TYPE = "shop.merchant_entry";
    /** 回调线程上的操作人：审批服务。谁批的记在 OpsKit 审批单上，这里的审计只记「审批通过生效」。 */
    static final String ACTOR = "OPSKIT";

    private final MerchantApplyMapper applies;
    private final OpsService ops;

    public MerchantEntryApprovalSubject(MerchantApplyMapper applies, @Lazy OpsService ops) {
        this.applies = applies;
        this.ops = ops;
    }

    @Override public String subjectType() { return SUBJECT_TYPE; }
    @Override public String processCode() { return "shop_merchant_entry"; }
    @Override public String processName() { return "入驻申请审核"; }
    @Override public String approverPerm() { return Perms.MERCHANT_APPLY_AUDIT; }
    @Override public Level level() { return Level.IMPORTANT; }

    @Override
    public Snapshot snapshot(String applyNo) {
        return snapshot(applyNo, Map.of());
    }

    @Override
    public Snapshot snapshot(String applyNo, Map<String, Object> proposal) {
        MchEntityApply a = find(applyNo);
        List<Fact> facts = new ArrayList<>();
        facts.add(Fact.of("申请主体", a.getName()));
        facts.add(Fact.of("主体类型", a.getLegalForm()));
        facts.add(Fact.of("行业 / 类目", nvl(a.getIndustry()) + " / " + nvl(a.getCategory())));
        facts.add(Fact.of("市场", a.getMarketCode()));
        facts.add(Fact.of("联系人", a.getContactName()));
        facts.add(Fact.of("提交人", a.getSubmittedBy() != null ? a.getSubmittedBy() : a.getUserNo()));
        facts.add(Fact.change("服务范围", a.getServiceScope(), str(proposal.get("serviceScope"), a.getServiceScope())));
        facts.add(Fact.of("覆盖社区", str(proposal.get("communityNos"), "沿用申请")));
        facts.add(Fact.of("授予类目码", str(proposal.get("grantCodes"), "沿用申请")));
        if (proposal.get("reason") != null) facts.add(Fact.of("审核意见", String.valueOf(proposal.get("reason"))));
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("marketCode", nvl(a.getMarketCode()));
        Set<String> scopes = a.getEntityNo() == null ? Set.of() : Set.of("merchant:" + a.getEntityNo());
        return new Snapshot("入驻审核 · " + a.getName(), "申请 " + applyNo, scopes, fields, facts);
    }

    @Override
    public void apply(String applyNo, long revision) {
        apply(applyNo, revision, Map.of());
    }

    @Override
    @SuppressWarnings("unchecked")
    public void apply(String applyNo, long revision, Map<String, Object> proposal) {
        asApprovalService(() -> ops.applyApprovedEntry(applyNo, revision,
                (String) proposal.get("reason"), (String) proposal.get("serviceScope"),
                (List<String>) proposal.get("communityNos"), (List<String>) proposal.get("grantCodes")));
    }

    @Override
    public void onClosed(String applyNo, long revision, Outcome outcome, String reason) {
        // 审批不同意的是「这份拟定结果」，不是申请本身：申请单留在 REVIEWING，运营改了再送，或在入驻页直接驳回
        log.info("[approval] 入驻 {} 版本 {} 审批未通过（{}）：{}", applyNo, revision, outcome, reason);
    }

    private MchEntityApply find(String applyNo) {
        MchEntityApply a = DataScopeContext.executeWithoutScope(() -> applies.selectOne(
                Wrappers.<MchEntityApply>lambdaQuery().eq(MchEntityApply::getApplyNo, applyNo).last("limit 1")));
        if (a == null) throw new ApprovalApplyException("NOT_FOUND", "入驻申请不存在：" + applyNo);
        return a;
    }

    /** 回调线程没有登录人与数据域：以「审批服务」身份、不限数据域执行。 */
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

    private static String nvl(String s) { return s == null ? "—" : s; }

    private static String str(Object v, String fallback) {
        if (v == null) return fallback;
        if (v instanceof List<?> l) return l.isEmpty() ? fallback : String.join(", ", l.stream().map(String::valueOf).toList());
        String s = String.valueOf(v);
        return s.isBlank() ? fallback : s;
    }
}
