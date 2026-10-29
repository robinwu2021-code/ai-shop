package ai.neargo.inventory.domain.service;

/**
 * 写动作审计（TDD §2.12）：谁、在哪个系统、对哪本账、改了什么、前后值、原因。只追加。
 *
 * <p>与业务写入同事务：业务回滚，审计跟着回滚 —— 审计记的是「发生了的事」，不是「尝试过的事」。
 */
public interface AuditService {

    void record(Caller caller, String action, String resourceType, String resourceId,
                Object before, Object after, String reason);
}
