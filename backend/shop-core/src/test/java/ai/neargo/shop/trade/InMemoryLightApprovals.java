package ai.neargo.shop.trade;

import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.spi.approval.LightApprovalPort;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** 测试用轻量审批：规则与 {@code LightApprovalPortImpl} 一致（同修订幂等、只批待批、审批人不是申请人、过期不批）。 */
final class InMemoryLightApprovals implements LightApprovalPort {
    final List<Approval> rows = new ArrayList<>();
    private final Clock clock;

    InMemoryLightApprovals(Clock clock) {
        this.clock = clock;
    }

    @Override
    public Approval submit(Submit s) {
        var existing = rows.stream().filter(a -> a.subjectType().equals(s.subjectType())
                && a.subjectRef().equals(s.subjectRef()) && a.revision() == s.revision()).findFirst();
        if (existing.isPresent()) return existing.get();
        var a = new Approval("LAP" + rows.size(), s.subjectType(), s.subjectRef(), s.revision(),
                s.autoApproved() ? APPROVED : PENDING, s.autoApproved() ? MODE_AUTO : MODE_MANUAL, s.submittedBy(),
                s.autoApproved() ? s.submittedBy() : null, s.autoApproved() ? clock.instant() : null, s.expiresAt(), 0);
        rows.add(a);
        return a;
    }

    @Override
    public Approval decide(String approvalNo, Decision d) {
        var a = rows.stream().filter(x -> x.approvalNo().equals(approvalNo)).findFirst()
                .orElseThrow(() -> BizException.of(ErrorCode.NOT_FOUND));
        String to = d.approve() ? APPROVED : REJECTED;
        if (to.equals(a.status()) && Objects.equals(d.approverNo(), a.approverNo())) return a;
        if (!PENDING.equals(a.status()) || d.approverNo().equals(a.submittedBy())
                || a.expiresAt() != null && !clock.instant().isBefore(a.expiresAt())) {
            throw BizException.of(ErrorCode.CONFLICT);
        }
        var decided = new Approval(a.approvalNo(), a.subjectType(), a.subjectRef(), a.revision(), to, a.approvalMode(),
                a.submittedBy(), d.approverNo(), clock.instant(), a.expiresAt(), a.version() + 1);
        rows.set(rows.indexOf(a), decided);
        return decided;
    }

    @Override
    public Optional<Approval> latest(String tenantNo, String subjectType, String subjectRef) {
        return rows.stream().filter(a -> a.subjectType().equals(subjectType) && a.subjectRef().equals(subjectRef))
                .max(Comparator.comparingLong(Approval::revision));
    }
}
