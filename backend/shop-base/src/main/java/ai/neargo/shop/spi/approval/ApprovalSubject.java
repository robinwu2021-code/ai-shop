package ai.neargo.shop.spi.approval;

import java.util.Map;
import java.util.Set;

/**
 * 一个接入审批的业务（TDD-OpsKit审批对接 §1.3）。业务域实现并声明为 Spring Bean，审批适配器按
 * {@link #subjectType()} 找到它：送审时取快照，执行节点触发时调 {@link #apply}，审批未通过时调 {@link #onClosed}。
 *
 * <p>命名约定：流程代号用下划线（{@code shop_store_opening}），业务对象类型用点（{@code shop.store_opening}），
 * 执行动作为 {@code <subjectType>.apply}。
 */
public interface ApprovalSubject {

    /** 业务对象类型，如 {@code shop.store_opening}；必须以 {@code shop.} 开头。 */
    String subjectType();

    /** OpsKit 流程（审批模板）代号，如 {@code shop_store_opening}。 */
    String processCode();

    /** 默认流程的显示名（中文），运营在流程配置里看到的名字。 */
    String processName();

    /** 默认流程里审批步骤的审批人权限码（{@code PERM:<码>}）。 */
    String approverPerm();

    /** 送审快照：标题、摘要、范围标签、流程条件字段。 */
    Snapshot snapshot(String subjectRef);

    /**
     * 执行节点触发：让业务生效。<b>必须幂等</b>（OpsKit 回复丢失时会重投同一事件）。
     * 对象已被改过 / 已不在待审态时抛 {@link ApprovalApplyException}（code = {@code STALE} 等）。
     */
    void apply(String subjectRef, long revision);

    /** 审批未通过（驳回 / 撤回 / 放弃）：业务回到可编辑状态。对象已变化时直接忽略。 */
    void onClosed(String subjectRef, long revision, Outcome outcome, String reason);

    enum Outcome { REJECTED, WITHDRAWN, CANCELLED }

    /**
     * @param scopes 范围标签，至少带 {@code merchant:<no>}（被限定范围的运营员工据此可见）
     * @param fields 流程条件可用的字段；适配器会追加 {@code revision}
     */
    record Snapshot(String title, String summary, Set<String> scopes, Map<String, Object> fields) {
        public Snapshot {
            scopes = scopes == null ? Set.of() : Set.copyOf(scopes);
            fields = fields == null ? Map.of() : Map.copyOf(fields);
        }
    }
}
