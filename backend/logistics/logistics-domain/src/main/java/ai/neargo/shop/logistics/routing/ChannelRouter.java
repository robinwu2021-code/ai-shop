package ai.neargo.shop.logistics.routing;

import ai.neargo.shop.logistics.capability.ChannelCapability;
import ai.neargo.shop.logistics.capability.PushReceiver;
import ai.neargo.shop.logistics.capability.StatusProbe;
import ai.neargo.shop.logistics.capability.TrackingSubscriber;
import ai.neargo.shop.logistics.config.LogisticsProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 这张运单该找哪家渠道（TDD-物流模块 §2.1.1，AC13）。
 *
 * <p>每种能力一条链：<b>门店 → 承运商 → 默认</b> 逐级找到一条，链上逐个过滤 ——
 * 未启用 / 不覆盖该承运商 / 不可用 的跳过。顺序就是配置里写的顺序，不靠 bean 注册顺序
 * （那种顺序依赖最难查）。
 *
 * <p>两条刻意的规则：
 * <ul>
 *   <li><b>订阅可用 ⇔ 同一渠道的推送接收也可用</b>。订阅了却收不到推送，订阅照样返回成功、
 *       运单从此沉默、不报任何错 —— 圆通眼下正是这个状态（订阅调试通过、推送待调试），
 *       有这条它会自动落到快递100，不需要有人记得先别把 yto 配进链里。</li>
 *   <li><b>订阅链尾不自动补 stub</b>。生产上两家凭据都没配时，stub 会让订阅「成功」而什么都没订上；
 *       链走完都不成就该是 FATAL、让运营看见。stub 只在显式写进链里时才用（开发环境）。</li>
 * </ul>
 */
@Component
public class ChannelRouter {

    private static final Logger log = LoggerFactory.getLogger(ChannelRouter.class);

    private final LogisticsProperties props;
    private final CarrierCodeBook codes;
    private final Map<String, TrackingSubscriber> subscribers;
    private final Map<String, PushReceiver> receivers;
    private final Map<String, StatusProbe> probes;

    public ChannelRouter(LogisticsProperties props, CarrierCodeBook codes,
                         List<TrackingSubscriber> subscribers, List<PushReceiver> receivers,
                         List<StatusProbe> probes) {
        this.props = props;
        this.codes = codes;
        this.subscribers = byName(subscribers);
        this.receivers = byName(receivers);
        this.probes = byName(probes);
        warnUnknown("subscribe", props.getRoutes().getSubscribe(), this.subscribers);
        warnUnknown("probe", props.getRoutes().getProbe(), this.probes);
    }

    /** 订阅候选，按链上顺序。空 = 这单没有任何渠道能订 */
    public List<TrackingSubscriber> subscribers(String storeNo, String carrier) {
        List<TrackingSubscriber> out = new ArrayList<>();
        for (String name : props.getRoutes().getSubscribe().resolve(storeNo, carrier)) {
            TrackingSubscriber s = subscribers.get(name);
            if (s != null && usable(s, carrier) && pushUsable(name)) {
                out.add(s);
            }
        }
        return out;
    }

    /** 探测候选，按链上顺序 */
    public List<StatusProbe> probes(String storeNo, String carrier) {
        List<StatusProbe> out = new ArrayList<>();
        for (String name : props.getRoutes().getProbe().resolve(storeNo, carrier)) {
            StatusProbe p = probes.get(name);
            if (p != null && usable(p, carrier)) {
                out.add(p);
            }
        }
        return out;
    }

    /** 推送入口按渠道名找接收器。不存在或不可用 → 空（Controller 回 404） */
    public Optional<PushReceiver> receiver(String channel) {
        PushReceiver r = receivers.get(channel);
        return r != null && props.enabled(channel) && r.available() ? Optional.of(r) : Optional.empty();
    }

    /** 我方承运商码 → 渠道编码。stub 这类全覆盖的渠道原样返回我方码 */
    public Optional<String> codeOf(String carrier, ChannelCapability c) {
        return c.coversAllCarriers() ? Optional.ofNullable(carrier) : codes.codeOf(carrier, c.channel());
    }

    /**
     * 指定渠道订阅（运营重放时点名用）：不可用时返回原因，可用时返回空。
     * 判据与 {@link #subscribers} 逐条相同 —— 两套判据的话，「总览说能用、重放说不能」就会出现。
     */
    public Optional<String> subscribeBlocker(String channel, String carrier) {
        TrackingSubscriber s = subscribers.get(channel);
        if (s == null) {
            return Optional.of("没有装 " + channel + " 的订阅实现");
        }
        Optional<String> r = blocker(s, carrier);
        if (r.isPresent()) {
            return r;
        }
        return pushUsable(channel) ? Optional.empty() : Optional.of("推送不可用，订阅随之不可用");
    }

    /** 指定渠道的订阅实现（先用 {@link #subscribeBlocker} 判过可用） */
    public Optional<TrackingSubscriber> subscriber(String channel) {
        return Optional.ofNullable(subscribers.get(channel));
    }

    /** 渠道总览（O4）用：每种能力装了哪些渠道 */
    public Map<String, TrackingSubscriber> installedSubscribers() {
        return subscribers;
    }

    public Map<String, PushReceiver> installedReceivers() {
        return receivers;
    }

    public Map<String, StatusProbe> installedProbes() {
        return probes;
    }

    /** 一个能力（不看承运商）为什么不可用；可用返回空 */
    public Optional<String> blocker(ChannelCapability c) {
        if (!props.enabled(c.channel())) {
            return Optional.of("配置里没启用");
        }
        return c.available() ? Optional.empty() : Optional.of("凭据没配");
    }

    private Optional<String> blocker(ChannelCapability c, String carrier) {
        Optional<String> r = blocker(c);
        if (r.isPresent()) {
            return r;
        }
        return c.coversAllCarriers() || codes.covers(c.channel(), carrier) ? Optional.empty()
                : Optional.of("不覆盖承运商 " + carrier + "（承运商编码表里没有这一家）");
    }

    /** 渠道编码 → 我方承运商码 */
    public Optional<String> carrierOf(String channel, String code) {
        return codes.carrierOf(channel, code);
    }

    private boolean usable(ChannelCapability c, String carrier) {
        return props.enabled(c.channel()) && c.available()
                && (c.coversAllCarriers() || codes.covers(c.channel(), carrier));
    }

    private boolean pushUsable(String channel) {
        return receiver(channel).isPresent();
    }

    private static <T extends ChannelCapability> Map<String, T> byName(List<T> list) {
        Map<String, T> m = new LinkedHashMap<>();
        for (T t : list) {
            T prev = m.putIfAbsent(t.channel(), t);
            if (prev != null) {
                throw new IllegalStateException("同一能力下渠道名重复：" + t.channel()
                        + "（" + prev.getClass().getSimpleName() + " / " + t.getClass().getSimpleName() + "）");
            }
        }
        return m;
    }

    /** 链里写了没装的渠道名 → 启动时 WARN 一次。不阻止启动：配置错不该让全站起不来 */
    private static void warnUnknown(String what, LogisticsProperties.Chain chain, Map<String, ?> installed) {
        List<String> all = new ArrayList<>(chain.getByDefault());
        chain.getByCarrier().values().forEach(all::addAll);
        chain.getByStore().values().forEach(all::addAll);
        all.stream().distinct().filter(n -> !installed.containsKey(n)).forEach(n ->
                log.warn("[logistics] {} 路由链里有渠道 {}，但没有装它的实现 —— 会被跳过", what, n));
    }
}
