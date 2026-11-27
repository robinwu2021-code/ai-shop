package ai.neargo.shop.spi.approval;

import java.util.List;
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

    /**
     * 审批等级（审核需求清单 §8，用户 2026-11-23 定）。决定启动时登记的默认流程：
     * 一级 1 步；二级 1 步 + 条件复核（送审字段 {@link #RECHECK_FIELD} 为 true 时出现）；三级固定 2 步。
     * 一律可以本人审批。默认二级。
     */
    default Level level() {
        return Level.IMPORTANT;
    }

    /** 复核步骤的审批人权限码（二级的条件复核、三级的第二步）；默认与审核同一个码。 */
    default String recheckPerm() {
        return approverPerm();
    }

    /**
     * 审批人是否必须与提交人不同、且各步不同人（默认否：用户 2026-11-23 定“一律可以本人审批”）。
     * 只有业务规则本身要求多人分离的才打开，如退款两人复核（TDD-基础能力库对齐 Q12）。
     */
    default boolean separateApprovers() {
        return false;
    }

    /** 二级流程里「要不要加复核」的条件字段：业务在 {@link Snapshot#fields()} 里放 {@code recheck=true} 即出现复核步骤。 */
    String RECHECK_FIELD = "recheck";

    enum Level {
        /** 一级 · 一般：配置启停、资料确认、低频运营审核。1 步，时限 72 小时。 */
        GENERAL(72),
        /** 二级 · 重要：影响经营、收款、归属、对外开放。1 步 + 条件复核，每步 24 小时，申请内容须带改前 → 改后。 */
        IMPORTANT(24),
        /** 三级 · 资金与密钥：动钱、动密钥、动资金路径、费率与分润发布。固定 2 步，每步 24 小时。 */
        FUNDS(24);

        private final int dueHours;

        Level(int dueHours) {
            this.dueHours = dueHours;
        }

        public int dueHours() {
            return dueHours;
        }
    }

    /** 送审快照：标题、摘要、范围标签、流程条件字段。 */
    Snapshot snapshot(String subjectRef);

    /** 带拟定结果的送审快照：申请内容里要把拟定结果列出来给审批人看。默认忽略拟定结果。 */
    default Snapshot snapshot(String subjectRef, Map<String, Object> proposal) {
        return snapshot(subjectRef);
    }

    /**
     * 执行节点触发：让业务生效。<b>必须幂等</b>（OpsKit 回复丢失时会重投同一事件）。
     * 对象已被改过 / 已不在待审态时抛 {@link ApprovalApplyException}（code = {@code STALE} 等）。
     */
    void apply(String subjectRef, long revision);

    /** 带拟定结果生效（送审时冻结的那一份）。默认忽略拟定结果。 */
    default void apply(String subjectRef, long revision, Map<String, Object> proposal) {
        apply(subjectRef, revision);
    }

    /** 审批未通过（驳回 / 撤回 / 放弃）：业务回到可编辑状态。对象已变化时直接忽略。 */
    void onClosed(String subjectRef, long revision, Outcome outcome, String reason);

    enum Outcome { REJECTED, WITHDRAWN, CANCELLED }

    /**
     * @param scopes 范围标签，至少带 {@code merchant:<no>}（被限定范围的运营员工据此可见）
     * @param fields 流程条件可用的字段；适配器会追加 {@code revision}；二级要复核时放 {@link #RECHECK_FIELD}=true
     * @param facts  审批详情里的申请内容（契约 C8，最多 50 条）；二级、三级必须带「改前 → 改后」
     */
    record Snapshot(String title, String summary, Set<String> scopes, Map<String, Object> fields, List<Fact> facts) {
        public Snapshot {
            scopes = scopes == null ? Set.of() : Set.copyOf(scopes);
            fields = fields == null ? Map.of() : Map.copyOf(fields);
            facts = facts == null ? List.of() : List.copyOf(facts);
        }

        public Snapshot(String title, String summary, Set<String> scopes, Map<String, Object> fields) {
            this(title, summary, scopes, fields, List.of());
        }
    }

    /** 申请内容的一行；{@code before} 非空时详情显示「改前 → 改后」。 */
    record Fact(String label, String value, String before, String hint) {
        public Fact {
            if (label == null || label.isBlank()) {
                throw new IllegalArgumentException("fact label required");
            }
        }

        public static Fact of(String label, String value) {
            return new Fact(label, value, null, null);
        }

        public static Fact change(String label, String before, String after) {
            return new Fact(label, after, before == null ? "" : before, null);
        }
    }
}
