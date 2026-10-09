package ai.neargo.shop.logistics.port;

import ai.neargo.shop.logistics.capability.StatusProbe;
import ai.neargo.shop.logistics.config.LogisticsProperties;
import ai.neargo.shop.logistics.domain.PhoneCipher;
import ai.neargo.shop.logistics.domain.WaybillProgress;
import ai.neargo.shop.logistics.domain.WaybillStatus;
import ai.neargo.shop.logistics.entity.LgsWaybill;
import ai.neargo.shop.logistics.entity.LgsWaybillNode;
import ai.neargo.shop.logistics.mapper.LogisticsMappers.WaybillMapper;
import ai.neargo.shop.logistics.mapper.LogisticsMappers.WaybillNodeMapper;
import ai.neargo.shop.logistics.probe.WaybillProber;
import ai.neargo.shop.logistics.routing.ChannelRouter;
import ai.neargo.shop.spi.logistics.LogisticsPort;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.LongSupplier;

/**
 * 物流页的读取（TDD-物流模块 M7，AC6 / AC11）。
 *
 * <p><b>缓存就是库</b>：不另设缓存层，{@code wx_status_checked_at} 就是 10 分钟的闸。
 * 只有「小程序 + 微信支付单 + 有 token + 未终态 + 距上次 ≥ 10 分钟」才顺带问一次微信 query_trace ——
 * 详情页不走这里（打开次数是物流页的数倍，在那里校正等于把这道闸打折；微信每用户每天也只有约 100 次）。
 * 问渠道失败不影响读：照样返回库里的。
 */
@Component
public class LogisticsPortImpl implements LogisticsPort {

    static final String WX = WaybillProber.WX;

    private final WaybillMapper waybills;
    private final WaybillNodeMapper nodes;
    private final ChannelRouter router;
    private final WaybillProber prober;
    private final LogisticsProperties props;
    private final LongSupplier clock;

    @Autowired
    public LogisticsPortImpl(WaybillMapper waybills, WaybillNodeMapper nodes, ChannelRouter router,
                             WaybillProgress progress, PhoneCipher phones, LogisticsProperties props) {
        this(waybills, nodes, router, progress, phones, props, System::currentTimeMillis);
    }

    LogisticsPortImpl(WaybillMapper waybills, WaybillNodeMapper nodes, ChannelRouter router, WaybillProgress progress,
                      PhoneCipher phones, LogisticsProperties props, LongSupplier clock) {
        this.waybills = waybills;
        this.nodes = nodes;
        this.router = router;
        this.props = props;
        this.clock = clock;
        this.prober = new WaybillProber(waybills, router, progress, phones);
    }

    @Override
    @Transactional
    public Optional<TrackView> track(TrackQuery q) {
        LgsWaybill w = find(q.bizType(), q.bizRef());
        if (w == null) {
            return Optional.empty();
        }
        long now = clock.getAsLong();
        boolean wxPlugin = "MP".equals(q.surface()) && LgsWaybill.PROFILE_WX.equals(w.getProfile()) && notBlank(w.getDisplayToken());
        List<StatusProbe> allowed = router.probes(w.getStoreNo(), w.getCarrier()).stream()
                .filter(p -> allowedOn(p.channel(), q.surface())).toList();
        boolean changed = false;
        if (wxPlugin && !WaybillStatus.isTerminal(w.getStatus()) && due(w, now)) {
            changed = allowed.stream().filter(p -> WX.equals(p.channel())).findFirst()
                    .map(p -> probe(w, p, now)).orElse(false);
        }
        if (q.refresh() && !allowed.isEmpty() && !WaybillStatus.isTerminal(w.getStatus())) {
            for (StatusProbe p : allowed) {
                if (WX.equals(p.channel()) && !due(w, now)) {
                    continue;   // 刚问过微信，不再问
                }
                if (probe(w, p, now)) {
                    changed = true;
                    break;
                }
            }
        }
        LgsWaybill cur = changed ? find(q.bizType(), q.bizRef()) : w;
        List<Node> list = nodes.selectList(Wrappers.<LgsWaybillNode>query()
                        .eq("shipment_no", cur.getShipmentNo()).orderByDesc("at")).stream()
                .map(n -> new Node(n.getAt(), n.getText(), n.getLocation(), n.getLatE6(), n.getLngE6(), n.getStatusCode()))
                .toList();
        boolean plugin = wxPlugin && notBlank(cur.getDisplayToken());
        return Optional.of(new TrackView(cur.getShipmentNo(), cur.getCarrier(), cur.getWaybillNo(), cur.getStatus(),
                cur.getSignedAt(), cur.getAtLocker() != null && cur.getAtLocker() == 1, list,
                plugin ? "wx-plugin" : "self-map", plugin ? cur.getDisplayToken() : null,
                cur.getLastEventAt(), !allowed.isEmpty()));
    }

    @Override
    public Map<String, Long> signedAtOf(Collection<String> subOrderNos) {
        Map<String, Long> out = new HashMap<>();
        if (subOrderNos == null || subOrderNos.isEmpty()) {
            return out;
        }
        for (LgsWaybill w : waybills.selectList(Wrappers.<LgsWaybill>query()
                .eq("biz_type", LgsWaybill.BIZ_SUB_ORDER).in("biz_ref", subOrderNos).isNotNull("signed_at"))) {
            out.put(w.getBizRef(), w.getSignedAt());
        }
        return out;
    }

    /** @return 有没有带来新进展。问失败照样返回库里的 */
    private boolean probe(LgsWaybill w, StatusProbe p, long now) {
        return prober.probe(w, p, now).changed();
    }

    private boolean due(LgsWaybill w, long now) {
        Long at = w.getWxStatusCheckedAt();
        return at == null || now - at >= props.getReadCacheMinutes() * 60_000L;
    }

    /** 渠道在配置里没列 = 全部界面放行；列了 = 只放行列出的（快递100 默认一个都不放行） */
    private boolean allowedOn(String channel, String surface) {
        List<String> s = props.getProbeSurfaces().get(channel);
        return s == null || s.contains(surface);
    }

    private LgsWaybill find(String bizType, String bizRef) {
        return waybills.selectOne(Wrappers.<LgsWaybill>query().eq("biz_type", bizType).eq("biz_ref", bizRef)
                .orderByDesc("id").last("limit 1"));
    }

    private static boolean notBlank(String s) {
        return s != null && !s.isBlank();
    }
}
