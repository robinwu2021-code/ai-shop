package ai.neargo.shop.invoice.legacy;

import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 发票留在主库的那部分数据的入口：通道开通申请、各类历史记录、邮件投递。
 *
 * <p>发票的业务表在自己的数据源上（ADR-066），和主库没有共同事务。这里提供两样东西：
 * <ul>
 *   <li>{@link #primary()}：主库上的事务，给只碰主库表的流程用；</li>
 *   <li>{@link #afterCommit(String, Runnable)}：发票事务提交之后再记一笔历史。</li>
 * </ul>
 *
 * <p>为什么历史记录放在提交之后：放在之前，发票事务回滚时主库已经留下一条「发生过」的记录，
 * 而重放逻辑会按这条记录回放一个不存在的结果。放在之后最坏是少一条历史，业务状态仍然是对的；
 * 调用方的重放都另有按业务状态的兜底。
 */
@Component
public class InvoiceHistory {

    private static final Logger log = LoggerFactory.getLogger(InvoiceHistory.class);

    private final JdbcTemplate jdbc;
    private final TransactionTemplate primary;

    public InvoiceHistory(@Qualifier("dataSource") DataSource dataSource,
            @Qualifier("transactionManager") PlatformTransactionManager manager) {
        this.jdbc = new JdbcTemplate(dataSource);
        this.primary = new TransactionTemplate(manager);
    }

    /** 主库上的手写 SQL（通道申请三张表、账户历史版、路由事件没有实体）。 */
    public JdbcTemplate jdbc() {
        return jdbc;
    }

    /** 主库事务。 */
    public TransactionTemplate primary() {
        return primary;
    }

    /**
     * 当前事务提交后在主库事务里执行 {@code write}；当前没有事务就立刻执行。
     *
     * <p>这里捕获数据访问异常是有业务目的的：业务已经提交，历史写不进去不能让调用方以为业务失败了。
     */
    public void afterCommit(String event, Runnable write) {
        Runnable guarded = () -> {
            try {
                primary.executeWithoutResult(status -> write.run());
            } catch (DataAccessException e) {
                log.warn("invoice.history.lost event={} error={}", event, e.getClass().getSimpleName());
            }
        };
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            guarded.run();
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                guarded.run();
            }
        });
    }
}
