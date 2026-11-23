package ai.neargo.shop.paybridge.approval;

import ai.neargo.common.data.scope.DataScopeContext;
import ai.neargo.shop.auth.LoginUser;
import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.spi.approval.ApprovalApplyException;
import ai.neargo.shop.spi.approval.ApprovalPort;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 支付域审批的公共件（方案-第一批审批工作流 T7、T8）。
 *
 * <p>支付域有独立的事务管理器（{@code payTxManager}，见 PayDataSourceConfig），刻意让跨域事务写不出来。
 * 所以支付域的审批<b>不在支付事务里送审</b>：业务先在支付事务里提交，成功后在平台事务里送审（{@link #submit}）；
 * 两步不是一个事务，送审失败的由各业务的补送任务找回（待审却没有审批单的，重新送）。
 */
@Component
public class PayApprovalSupport {

    /** 回调线程上的操作人：审批服务。谁批的记在 OpsKit 审批单上。 */
    public static final String ACTOR = "OPSKIT";

    private final ObjectProvider<ApprovalPort> approvals;
    private final TransactionTemplate tx;

    public PayApprovalSupport(ObjectProvider<ApprovalPort> approvals, PlatformTransactionManager transactionManager) {
        this.approvals = approvals;
        this.tx = new TransactionTemplate(transactionManager);
    }

    /** 该业务是否已切到审批。 */
    public boolean enabled(String subjectType) {
        ApprovalPort port = approvals.getIfAvailable();
        return port != null && port.enabled(subjectType);
    }

    /** 在平台事务里送审；同一修订已送过则跳过（幂等）。返回是否真的送了。 */
    public boolean submit(String subjectType, String ref, long revision, String staffNo, String displayName, Map<String, Object> proposal) {
        ApprovalPort port = approvals.getIfAvailable();
        if (port == null || !port.enabled(subjectType)) return false;
        boolean already = port.latest(subjectType, ref).filter(l -> l.revision() == revision).isPresent();
        if (already) return false;
        tx.executeWithoutResult(s -> port.submit(subjectType, ref, revision,
                new ApprovalPort.Submitter(staffNo == null || staffNo.isBlank() ? "SYSTEM" : staffNo, displayName), proposal));
        return true;
    }

    /** 回调线程没有登录人与数据域：以「审批服务」身份、不限数据域执行。 */
    public static <T> T asApprovalService(Supplier<T> work) {
        var previous = SecurityContextHolder.getContext();
        try {
            var ctx = SecurityContextHolder.createEmptyContext();
            ctx.setAuthentication(new UsernamePasswordAuthenticationToken(
                    LoginUser.operator(ACTOR, "审批服务", List.of(), List.of("*")), null, List.of()));
            SecurityContextHolder.setContext(ctx);
            return DataScopeContext.executeWithoutScope(work);
        } finally {
            SecurityContextHolder.setContext(previous);
        }
    }

    /**
     * 业务拒绝生效 → {@link ApprovalApplyException}：版本 / 状态冲突算 STALE，其余带上业务错误码，显示在「生效失败」上。
     */
    public static RuntimeException failure(RuntimeException e) {
        if (e instanceof ApprovalApplyException a) return a;
        String code = e instanceof BizException b ? b.errorCode().name() : e.getMessage();
        if (code == null) code = e.getClass().getSimpleName();
        boolean stale = (e instanceof BizException b && (b.errorCode() == ErrorCode.POINTS_REVISION_CHANGED || b.errorCode() == ErrorCode.CONFLICT))
                || code.contains("REVISION") || code.contains("STATE_CONFLICT");
        return new ApprovalApplyException(stale ? ApprovalApplyException.STALE : code, e.getMessage() == null ? code : e.getMessage());
    }

    /** 最小单位 → 元（两位小数），申请内容展示用。 */
    public static String money(long minor) {
        return BigDecimal.valueOf(minor, 2).toPlainString();
    }
}
