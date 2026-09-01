package ai.neargo.shop.pay.svc;

import java.lang.reflect.Proxy;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * <b>支付域独立之后，还没有远程实现的业务侧 Port。</b>
 *
 * <h2>这个类是一份账，不是一个功能</h2>
 * 支付域在单体里通过 Port 调业务侧，那些 Port 的实现都在业务模块里
 * （{@code shop-core} / {@code shop-merchant} / …），而这个进程<b>刻意不引业务模块</b>。
 * 于是每一个 Port 都需要一个 HTTP 实现 —— 那是 C4 的主体工作。
 *
 * <p>在它们写出来之前，这里给每个 Port 一个<b>调用即抛</b>的桩，
 * 让进程能装配起来。<b>桩必须抛，不能返回 null 或空集</b>：
 * 返回空的话，这个进程看起来能跑，而它算出来的每一笔账都是错的 ——
 * 「没有商家信息」会被当成「这个商家没有配置」，
 * 「查不到订单」会被当成「订单不存在」，两者都不会报错。
 *
 * <h2>清单本身就是工作量</h2>
 * 下面 11 个 Port 是 2026-09-01 从 pay-domain 的源码里量出来的。
 * 每写完一个远程实现，就从这里删掉一行 —— <b>这个类空了，C4 就完成了</b>。
 *
 * <p>剩下三个 Port（{@code PointsPort} / {@code SelfOperatedExposurePort} /
 * {@code SettlePort}）不在此列：那是<b>支付域提供给别人的</b>，
 * 由 pay-domain 自己实现，独立之后它们变成这个进程的 {@code /internal} 入口。
 */
@Configuration
public class PendingRemotePortsConfig {

    /**
     * 还缺远程实现的 Port。**顺序按 pay-domain 里的用量排**，
     * 前面的先做收益最大。
     */
    /*
     * ⚠️ **这张表会锈。**
     *
     * 每当某个 Port 在 pay 这一侧有了本地实现（比如 M1 把通道主数据搬进来时
     * pay-channel 就带上了 PayQueryPortImpl），这里的桩就<b>多余了</b> ——
     * 而多余的表现不是「没人用」，是<b>两个 bean 撞在一起，pay-svc 起不来</b>：
     * 「required a single bean, but 2 were found」。
     *
     * 2026-09-02 加启动冒烟时撞到 PayQueryPort 这一条。
     * 加/删 pay 侧实现之后，要回来看一眼这张表。
     */
    private static final Class<?>[] PENDING = {
        ai.neargo.shop.spi.user.MerchantQueryPort.class,        // 6 个文件在用
        ai.neargo.shop.spi.trade.SettleSourcePort.class,        // 3
        ai.neargo.shop.spi.trade.RefundSplitBackPort.class,     // 1
        ai.neargo.shop.spi.user.PickupQueryPort.class,          // 1
    };

    @Bean
    static org.springframework.beans.factory.config.BeanFactoryPostProcessor pendingPorts() {
        return factory -> {
            for (Class<?> port : PENDING) {
                factory.registerSingleton(port.getSimpleName(), stub(port));
            }
        };
    }

    /**
     * 造一个「调用即抛」的桩。
     *
     * <p>用动态代理而不是手写 11 个类：手写的那 11 个类里，
     * 每个方法都要有人决定返回什么 —— 而<b>这里的正确答案是「不该有返回值」</b>，
     * 手写反而给了填一个假值的机会。
     */
    private static Object stub(Class<?> port) {
        return Proxy.newProxyInstance(port.getClassLoader(), new Class<?>[]{port},
                (proxy, method, args) -> {
                    /*
                     * **Object 的三个方法必须放行。**
                     *
                     * 原本只放行了 toString，而 Spring 在把 bean 放进容器、
                     * 做依赖比较、建代理链时会调 equals 与 hashCode ——
                     * 于是这个「调用即抛」的桩<b>在容器自己用它的时候就炸了</b>，
                     * 表现是 pay-svc 起不来，报错说
                     * 「PayQueryPort.equals 还没有远程实现」。
                     *
                     * 那句话本身是对的，只是它不该拦 equals：
                     * equals 不是这个 Port 的业务方法，它是 Object 的。
                     *
                     * 2026-09-02 加 pay-svc 启动冒烟时第一次跑就抓到 ——
                     * 而在此之前它一直「装得起来」这句话没有任何东西验过。
                     */
                    String name = method.getName();
                    if ("toString".equals(name)) {
                        return "PendingRemotePort(" + port.getSimpleName() + ")";
                    }
                    if ("equals".equals(name) && args != null && args.length == 1) {
                        return proxy == args[0];
                    }
                    if ("hashCode".equals(name)) {
                        return System.identityHashCode(proxy);
                    }
                    throw new UnsupportedOperationException(
                            "支付域独立形态下 " + port.getSimpleName() + "." + method.getName()
                            + " 还没有远程实现。这个进程今天**装得起来但不接流量** —— "
                            + "见 PendingRemotePortsConfig 的类注释。");
                });
    }
}
