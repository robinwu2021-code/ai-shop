package ai.neargo.shop.svc;

/**
 * 进程名。<b>这个列表就是「系统由哪几个进程组成」的答案。</b>
 *
 * <p>用常量而不是字符串字面量：拼错一个字母的表现是
 * {@code ServiceLocator} 返回空，而调用方多半会把它当成「对方没起来」——
 * 那会让人去查一个根本没问题的服务。
 */
public final class ServiceName {

    /** 主应用：三端 controller、订单、商品、商家 …… 也是 {@code /internal} 的提供方 */
    public static final String PLATFORM = "PLATFORM";

    /** 支付域独立形态（形态 B）。内嵌形态下不存在这个进程 —— 那时调用走进程内 */
    public static final String PAY = "PAY";

    /** 定时任务调度器。它<b>不接受调用</b>，只调别人；列在这里是为了名字统一 */
    public static final String JOB = "JOB";

    private ServiceName() {
    }
}
