package ai.neargo.shop.event;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * 把一个动作推迟到<b>业务事务提交之后</b>执行。
 *
 * <h2>它解决什么</h2>
 * 跨域调用（下单 → 结算、下单 → 积分）今天写在业务事务中段。
 * 在单体里这看着是安全的：一起提交、一起回滚。但支付域一旦独立成进程，
 * <b>就没有共享事务了</b> —— 而那时这些调用会变成：
 *
 * <ul>
 *   <li>业务回滚了、对面已经写了 → <b>一条对不上任何单的账</b>；</li>
 *   <li>业务提交了、对面没写成 → 一笔没有结算单的已支付订单。</li>
 * </ul>
 *
 * <p>第一种更糟：它是<b>凭空多出来的钱</b>，而删账不可逆，只能人工判断。
 * 第二种可以自动补（{@code generateForOrder} 幂等），资金巡检 I1 每小时兜一次。
 *
 * <p>所以规矩是：<b>跨域写一律推迟到提交之后</b>。这样第一种在物理上就不会发生 ——
 * 业务回滚时这个动作根本没被执行过。
 *
 * <h2>为什么失败只记日志、不往上抛</h2>
 * 到这一步<b>业务事务已经提交了</b>。往上抛的话，调用方（支付回调）看到的是失败，
 * 而实际上订单已经支付成功 —— 通道会重发回调，而 {@code markPaid} 是幂等的，
 * 直接返回，于是<b>那个动作再也不会被执行</b>，异常却每次都抛。
 * 症状是「支付回调一直报错，订单却是好的」，排查方向会指向回调链路，
 * 而真因在这里。
 *
 * <p>所以这里吞掉异常并打 error，把「最终要发生」这件事交给不变式巡检。
 * <b>这不是把错误藏起来</b> —— 它换了一层兜底：从「让调用方重试」
 * 换成「让巡检发现并修复」，而后者恰好是拆分之后唯一可靠的那一层。
 *
 * <h2>这是通往 Outbox 的一步，不是替代它</h2>
 * 完整形态是把事件写进 {@code sys_outbox}（与业务同事务落库）再异步投递，
 * 那样进程在提交后、执行前崩溃也不会丢。这里的版本在那个窗口里会丢 ——
 * <b>而这正是资金巡检存在的理由</b>：它不看消息只看事实，
 * 丢了照样能发现并补上。
 */
public final class AfterCommit {

    private static final Logger log = LoggerFactory.getLogger(AfterCommit.class);

    private AfterCommit() {
    }

    /**
     * @param label  出错时写进日志的名字 —— 要能一眼看出是哪条链路
     * @param action 事务提交后执行的动作。**必须幂等**：巡检补做时会再跑一次
     */
    public static void run(String label, Runnable action) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            /*
             * 没有事务时立刻执行 —— 语义等价（"提交之后"在没有事务时就是"现在"）。
             * 不这么写的话，任何在事务外调用它的路径会**静默地什么都不做**，
             * 而那种缺陷不会报错：不变式巡检会把它补上，于是看起来一切正常，
             * 只是每一笔都晚了一小时。
             */
            runSafely(label, action);
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                runSafely(label, action);
            }
        });
    }

    private static void runSafely(String label, Runnable action) {
        try {
            action.run();
        } catch (RuntimeException e) {
            log.error("[after-commit] {} 失败 —— 业务已提交，这一步留给不变式巡检补做：{}",
                    label, e.getMessage(), e);
        }
    }
}
