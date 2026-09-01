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
    private static final Class<?>[] PENDING = {
        ai.neargo.shop.spi.user.MerchantQueryPort.class,        // 6 个文件在用
        ai.neargo.shop.spi.platform.SettingPort.class,          // 3
        ai.neargo.shop.spi.trade.SettleSourcePort.class,        // 3
        ai.neargo.shop.spi.pay.PayQueryPort.class,              // 1
        ai.neargo.shop.spi.product.PointsRulePort.class,        // 1
        ai.neargo.shop.spi.trade.OrderRepairPort.class,         // 1
        ai.neargo.shop.spi.trade.RefundSplitBackPort.class,     // 1
        ai.neargo.shop.spi.user.MerchantAdminPort.class,        // 1
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
                    if ("toString".equals(method.getName())) {
                        return "PendingRemotePort(" + port.getSimpleName() + ")";
                    }
                    throw new UnsupportedOperationException(
                            "支付域独立形态下 " + port.getSimpleName() + "." + method.getName()
                            + " 还没有远程实现。这个进程今天**装得起来但不接流量** —— "
                            + "见 PendingRemotePortsConfig 的类注释。");
                });
    }
}
