package ai.neargo.shop.spi.approval;

import java.util.Optional;

/**
 * 审批出口（TDD-OpsKit审批对接 §1.3）。业务域只认这个接口，不认 OpsKit。
 *
 * <p>实现只在启用 OpsKit 时存在（Maven profile {@code opskit} + {@code shop.opskit.enabled=true}），
 * 业务方一律用 {@code ObjectProvider<ApprovalPort>} 取，取不到或 {@link #enabled} 为 false 时走原有流程。
 */
public interface ApprovalPort {

    /** 该业务是否已切到审批（{@code shop.opskit.enabled-subjects} 里登记了它）。 */
    boolean enabled(String subjectType);

    /**
     * 送审。<b>必须在业务事务内调用</b>：同一事务里写 {@code approval_link} 与发件箱，提交后异步投递到 OpsKit；
     * OpsKit 不可用时业务提交照常成功，投递退避重试。
     * 同一 {@code subjectType + subjectRef + revision} 重复调用幂等。
     *
     * @param revision 业务对象在送审这一刻的版本；执行前据此判断对象有没有被改过（STALE）
     */
    void submit(String subjectType, String subjectRef, long revision, Submitter submitter);

    /** 该业务对象最近一次送审的关联记录。 */
    Optional<Link> latest(String subjectType, String subjectRef);

    /** 提交人：ai-shop 运营员工号与显示名（OpsKit 据员工号判断本人审批）。 */
    record Submitter(String staffNo, String displayName) {
        public Submitter {
            if (staffNo == null || staffNo.isBlank()) {
                throw new IllegalArgumentException("submitter staffNo required");
            }
        }
    }

    /** 业务侧看到的审批关联（状态以 OpsKit 为准，这里是回写的投影）。 */
    record Link(String subjectType, String subjectRef, long revision, String processCode,
                String approvalNo, String instanceNo, String status, String reason) {
    }
}
