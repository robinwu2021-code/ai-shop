package ai.neargo.shop.trade.service.impl;

import ai.neargo.shop.spi.product.PointsRulePort;
import ai.neargo.shop.spi.settle.PointsPort;

import java.util.Optional;

/**
 * 把 product 域的积分规则翻成支付域的形状（M9 · 2026-09-01）。
 *
 * <h2>为什么这么一行代码值得单独一个类</h2>
 * 因为它是<b>整条交接上唯一会静默出错的一步</b>，而且错法只有一种：
 *
 * <ul>
 *   <li>{@code Optional.empty()} —— 商品与类目两层都没配。
 *       支付域据此用<b>平台兜底比例</b>；</li>
 *   <li>{@code EarnRule(FIXED, 0)} —— <b>明确配了 0 分</b>。储值卡就是这么配的，
 *       支付域该如实发 0。</li>
 * </ul>
 *
 * <p>这两者必须一路分开。把 {@code orElse(null)} 写成
 * {@code orElse(new EarnRule(FIXED, 0))} 看起来更「安全」（少一个 null），
 * 实际是把两种含义合成了一个 —— <b>储值卡从「发 0 分」变成「按兜底比例发分」，
 * 而两边都不报错</b>。这是多层配置最常见的那个 bug，
 * {@link PointsRulePort#ruleFor} 的注释里已经点过一次名，M9 给了它一次新的机会。
 *
 * <p>藏在 {@code OrderServiceImpl} 的两千行里的话，它没有名字、也没有测试
 * 能直接指着它 —— 试过：守卫写在支付域那一侧，消融这一行<b>不变红</b>，
 * 因为那些用例是自己构造 {@code EarnLine} 的，压根不经过这里。
 *
 * <h2>为什么翻译而不是复用 product 的 record</h2>
 * 支付域不该认识 product 的类型。编译期还连着的依赖不算断，只是看不见了。
 */
public final class PointsRuleHandoff {

    private PointsRuleHandoff() {
    }

    /**
     * @param configured {@link PointsRulePort#ruleFor} 的原样返回
     * @return 支付域用的规则；<b>{@code null} 表示「两层都没配」</b>，
     *         由支付域落到平台兜底比例
     */
    public static PointsPort.EarnRule toPayRule(Optional<PointsRulePort.EarnRule> configured) {
        return configured
                .map(r -> new PointsPort.EarnRule(r.mode(), r.value()))
                .orElse(null);
    }
}
