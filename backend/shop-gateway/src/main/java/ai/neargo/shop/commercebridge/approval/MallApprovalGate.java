package ai.neargo.shop.commercebridge.approval;

import ai.neargo.commerce.api.common.ActorContext;
import ai.neargo.commerce.starter.http.OpsApprovalGatePort;
import ai.neargo.shop.paybridge.approval.PayApprovalSupport;
import ai.neargo.shop.spi.approval.ApprovalPort;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/**
 * 商城资金动作的审批门禁（T9a、T9b）：第一次点 → 送审；审批中 → 提示等待；
 * 同一修订已放行 → 让请求进入商城，以运营本人的会话执行。业务已不在待处理状态 → 直接交给商城（重放 / 冲突由商城判）。
 */
@Component
public class MallApprovalGate implements OpsApprovalGatePort {

    static final Set<String> RELEASED = Set.of("APPLIED", "APPROVED");
    static final Set<String> WAITING = Set.of("PENDING_SUBMIT", "PENDING");

    private final Map<String, MallGateSubject> subjects = new LinkedHashMap<>();
    private final ObjectProvider<ApprovalPort> approvals;
    private final PayApprovalSupport support;

    public MallApprovalGate(List<MallGateSubject> subjects, ObjectProvider<ApprovalPort> approvals, PayApprovalSupport support) {
        subjects.forEach(s -> this.subjects.put(s.action(), s));
        this.approvals = approvals;
        this.support = support;
    }

    @Override
    public Optional<Object> hold(String action, ActorContext operator, String storeNo, String orderNo, String subjectNo, Object request) {
        var subject = subjects.get(action);
        if (subject == null || subjectNo == null || subjectNo.isBlank() || !support.enabled(subject.subjectType())) return Optional.empty();
        var ref = new MallGateSubject.Ref(operator.tenantNo(), storeNo, orderNo, subjectNo);
        if (!subject.awaiting(ref)) return Optional.empty();
        var proposal = MallGateSubject.proposal(request);
        long revision = subject.revision(ref, proposal);
        if (revision < 0) return Optional.empty(); // 对象不存在：交给商城报错
        var latest = approvals.getObject().latest(subject.subjectType(), ref.toString())
                .filter(l -> l.revision() == revision);
        if (latest.isPresent()) {
            String status = latest.get().status();
            if (RELEASED.contains(status)) return Optional.empty();
            return Optional.of(view(subject, ref, WAITING.contains(status) ? "PENDING" : status, latest.get().reason()));
        }
        support.submit(subject.subjectType(), ref.toString(), revision, operator.principalNo(), null, proposal);
        return Optional.of(view(subject, ref, "SUBMITTED", null));
    }

    private static Map<String, Object> view(MallGateSubject subject, MallGateSubject.Ref ref, String status, String reason) {
        var m = new LinkedHashMap<String, Object>();
        m.put("approvalStatus", status); // SUBMITTED / PENDING / REJECTED / WITHDRAWN / CANCELLED
        m.put("subjectType", subject.subjectType());
        m.put("subjectRef", ref.toString());
        if (reason != null) m.put("reason", reason);
        return m;
    }
}
