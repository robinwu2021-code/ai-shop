package ai.neargo.shop.pay.approval.service.impl;

import ai.neargo.common.data.scope.DataScopeContext;
import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.pay.approval.entity.PayApproval;
import ai.neargo.shop.pay.approval.service.ApprovalService;
import ai.neargo.shop.pay.mapper.acquiring.AcquiringMappers.PayApprovalMapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Objects;
import org.springframework.stereotype.Service;

/** {@link ApprovalService} 的实现（TDD-支付服务-I3支付单退款流水 S6）。同一人判定在服务层与表的 CHECK 两处都有 */
@Service
public class ApprovalServiceImpl implements ApprovalService {

    private final PayApprovalMapper approvals;

    public ApprovalServiceImpl(PayApprovalMapper approvals) {
        this.approvals = approvals;
    }

    @Override
    public PayApproval open(String subjectType, Long subjectId, String sha, Long amountMinor, String currency,
                            String requestedBy) {
        String key = subjectType + "|" + subjectId;
        PayApproval existing = DataScopeContext.executeWithoutScope(() -> approvals.selectOne(
                Wrappers.<PayApproval>lambdaQuery().eq(PayApproval::getOpenKey, key)));
        if (existing != null) {
            return existing;
        }
        PayApproval a = new PayApproval();
        a.setSubjectType(subjectType);
        a.setSubjectId(subjectId);
        a.setSubjectSha256(sha);
        a.setAmountMinor(amountMinor);
        a.setCurrency(currency);
        a.setRequestedBy(requestedBy);
        a.setStatus("PENDING_FIRST");
        a.setOpenKey(key);
        DataScopeContext.executeWithoutScope(() -> approvals.insert(a));
        return a;
    }

    @Override
    public PayApproval decide(Long approvalId, String reviewer, boolean approve, String reason) {
        PayApproval a = DataScopeContext.executeWithoutScope(() -> approvals.selectById(approvalId));
        if (a == null) {
            throw BizException.of(ErrorCode.PAY_ORDER_NOT_FOUND);
        }
        boolean first = "PENDING_FIRST".equals(a.getStatus());
        if (!first && !"PENDING_SECOND".equals(a.getStatus())) {
            throw BizException.of(ErrorCode.PAY_ORDER_STATE, a.getStatus());
        }
        if (reviewer == null || reviewer.isBlank() || Objects.equals(reviewer, a.getRequestedBy())
                || !first && Objects.equals(reviewer, a.getFirstReviewer())) {
            throw BizException.of(ErrorCode.PAY_APPROVAL_SAME_PERSON);
        }
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        var w = Wrappers.<PayApproval>lambdaUpdate().eq(PayApproval::getId, a.getId())
                .eq(PayApproval::getStatus, a.getStatus()).setSql("version = version + 1");
        String to;
        if (!approve) {
            to = "REJECTED";
            w.set(PayApproval::getRejectReason, reason).set(PayApproval::getOpenKey, null);
        } else {
            to = first ? "PENDING_SECOND" : "APPROVED";
            if (!first) {
                w.set(PayApproval::getOpenKey, null);
            }
        }
        if (first) {
            w.set(PayApproval::getFirstReviewer, reviewer).set(PayApproval::getFirstDecidedAt, now);
        } else {
            w.set(PayApproval::getSecondReviewer, reviewer).set(PayApproval::getSecondDecidedAt, now);
        }
        w.set(PayApproval::getStatus, to);
        if (DataScopeContext.executeWithoutScope(() -> approvals.update(null, w)) != 1) {
            throw BizException.of(ErrorCode.PAY_ORDER_STATE, a.getStatus());
        }
        return DataScopeContext.executeWithoutScope(() -> approvals.selectById(approvalId));
    }
}
