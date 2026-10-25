package ai.neargo.shop.pay.channel.handler;

import ai.neargo.shop.pay.channel.api.ChannelKey;
import ai.neargo.shop.pay.channel.api.PayScene;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * 按五维键解析处理器（Registry，渠道接入统一封装设计 §7）。替代 {@code PayGatewayRouter} 的「按渠道名一张 Map」。
 *
 * <p>最具体者优先：精确地区 > 任意地区；精确场景 > ANY。<b>启动时校验，任一不过即启动失败</b>：
 * <ol>
 *   <li>同一个键能被两个<b>同等具体</b>的处理器命中 —— 谁生效取决于注入顺序，等于随机；</li>
 *   <li>运营开着的产品（{@code pay_channel_product} ENABLED）找不到收款处理器 —— 表里开了、代码没实现，
 *       收银台会列出一个点了必失败的方式。</li>
 * </ol>
 * 第三项（处理器声明的渠道要有回调处理器）随统一回调（S7）一起立。
 */
public final class ChannelHandlerRegistry {

    private record Entry(ChannelKey.Pattern pattern, ChannelHandler handler) {
    }

    private final List<Entry> entries;

    public ChannelHandlerRegistry(List<? extends ChannelHandler> handlers, ChannelProductCatalog catalog) {
        List<Entry> all = new ArrayList<>();
        for (ChannelHandler h : handlers) {
            for (ChannelKey.Pattern p : h.supports()) {
                all.add(new Entry(p, h));
            }
        }
        all.sort(Comparator.comparingInt((Entry e) -> e.pattern().specificity()).reversed());
        this.entries = List.copyOf(all);
        verifyNoAmbiguity();
        verifyEnabledProducts(catalog);
    }

    /** 找不到 → 空对象（明确失败），不是 null、更不是别的渠道的处理器 */
    public ChannelHandler resolve(ChannelKey key) {
        ChannelHandler best = null;
        int bestSpec = -1;
        for (Entry e : entries) {
            if (e.pattern().specificity() < bestSpec) {
                break;
            }
            if (e.pattern().matches(key)) {
                best = e.handler();
                bestSpec = e.pattern().specificity();
            }
        }
        return best != null ? best : new UnsupportedHandler(key);
    }

    public boolean has(ChannelKey key) {
        return !(resolve(key) instanceof UnsupportedHandler);
    }

    private void verifyNoAmbiguity() {
        for (int i = 0; i < entries.size(); i++) {
            for (int j = i + 1; j < entries.size(); j++) {
                Entry a = entries.get(i);
                Entry b = entries.get(j);
                if (a.handler() != b.handler() && overlaps(a.pattern(), b.pattern())
                        && a.pattern().specificity() == b.pattern().specificity()) {
                    throw new IllegalStateException("两个处理器同等具体地命中同一个键：" + a.pattern() + " ← "
                            + a.handler().getClass().getSimpleName() + " / " + b.handler().getClass().getSimpleName()
                            + "。谁生效会取决于注入顺序 —— 收窄其中一个的地区或场景");
                }
            }
        }
    }

    private static boolean overlaps(ChannelKey.Pattern a, ChannelKey.Pattern b) {
        return a.payChannel().equals(b.payChannel()) && a.accessMode() == b.accessMode()
                && a.operation() == b.operation()
                && (a.market() == null || b.market() == null || a.market().equals(b.market()))
                && (a.scene() == b.scene() || a.scene() == PayScene.ANY
                        || b.scene() == PayScene.ANY);
    }

    private void verifyEnabledProducts(ChannelProductCatalog catalog) {
        Set<String> missing = new TreeSet<>();
        for (ChannelKey key : catalog.enabledPayKeys()) {
            if (!has(key)) {
                missing.add(key.toString());
            }
        }
        if (!missing.isEmpty()) {
            throw new IllegalStateException("运营开着、代码没实现的支付产品（pay_channel_product ENABLED 但没有收款处理器）："
                    + missing + "。要么实现处理器，要么把产品停用 —— 否则收银台会列出一个点了必失败的方式");
        }
    }

    /** 供排障：键 → 处理器类名 */
    public Map<String, String> describe() {
        Map<String, String> m = new java.util.LinkedHashMap<>();
        entries.forEach(e -> m.put(e.pattern().toString(), e.handler().getClass().getSimpleName()));
        return m;
    }
}
