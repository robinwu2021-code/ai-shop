package ai.neargo.shop.food.service.support;

import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * 交易先、行业跟（TDD-餐饮域迁移新库 §2.3 W 类）：交易事务里产生的行业写入，等交易提交后再做。
 *
 * <p>行业库与门店交易库不在一个事务里。交易回滚时行业不应留下痕迹，所以不在交易事务内写行业；
 * 提交后写。行业写入必须按业务键可重复执行；这里不重试，失败的异常照常抛给调用方，
 * 漏掉的写入由各自的扫描补做（交易已提交，重放请求只会拿到首次结果）。
 * 没有交易事务时（调用方本来就不在事务里）直接执行。
 */
public final class IndustryFollowUp {
    private IndustryFollowUp() {}

    public static void afterTradeCommit(Runnable write) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            write.run();
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(
                new TransactionSynchronization() {
                    @Override
                    public void afterCommit() {
                        write.run();
                    }
                });
    }
}
