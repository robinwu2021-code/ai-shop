package ai.neargo.shop.logistics.port;

import ai.neargo.shop.logistics.capability.StatusProbe;
import ai.neargo.shop.logistics.config.LogisticsProperties;
import ai.neargo.shop.logistics.domain.PhoneCipher;
import ai.neargo.shop.logistics.domain.WaybillProgress;
import ai.neargo.shop.logistics.domain.WaybillStatus;
import ai.neargo.shop.logistics.registration.ChannelSubscriptionStore;
import ai.neargo.shop.logistics.registration.ShipmentRegistrar;
import ai.neargo.shop.logistics.wxbind.WxFollowExecutor;
import ai.neargo.shop.logistics.capability.WaybillFollower;
import ai.neargo.shop.logistics.entity.LgsChannelSubscription;
import ai.neargo.shop.logistics.entity.LgsWaybill;
import ai.neargo.shop.logistics.entity.LgsWaybillNode;
import ai.neargo.shop.logistics.mapper.LogisticsMappers.WaybillMapper;
import ai.neargo.shop.logistics.mapper.LogisticsMappers.WaybillNodeMapper;
import ai.neargo.shop.logistics.mapper.LogisticsMappers.ChannelSubscriptionMapper;
import ai.neargo.shop.logistics.probe.WaybillProber;
import ai.neargo.shop.logistics.routing.ChannelRouter;
import ai.neargo.shop.spi.logistics.LogisticsPort;
import ai.neargo.shop.spi.logistics.TraceProvider;
import ai.neargo.shop.spi.logistics.TraceResult;
import ai.neargo.shop.spi.logistics.TraceStatus;
import ai.neargo.shop.spi.logistics.ShipmentSourcePort;
import ai.neargo.shop.event.SysOutbox;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.annotation.Isolation;

import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.LinkedHashMap;
import java.util.Objects;
import java.util.function.LongSupplier;
import tools.jackson.databind.ObjectMapper;

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
    private List<TraceProvider> precheckProviders = List.of();
    private WaybillFollower follower;
    private ChannelSubscriptionStore subscriptions;
    private ShipmentSourcePort source;
    private ShipmentRegistrar registrar;
    private WxFollowExecutor followExecutor;
    private ChannelSubscriptionMapper channelRows;
    private JdbcTemplate precheckDb;
    private final ObjectMapper json = new ObjectMapper();

    @Autowired(required = false)
    public void setPrecheckProviders(List<TraceProvider> providers) { this.precheckProviders = providers; }
    @Autowired(required = false)
    public void setFollower(WaybillFollower follower) { this.follower = follower; }
    @Autowired(required = false)
    public void setSubscriptions(ChannelSubscriptionStore subscriptions) { this.subscriptions = subscriptions; }
    @Autowired(required = false)
    public void setSource(ShipmentSourcePort source) { this.source = source; }
    @Autowired(required = false)
    public void setRegistrar(ShipmentRegistrar registrar) { this.registrar = registrar; }
    @Autowired(required = false)
    public void setFollowExecutor(WxFollowExecutor followExecutor) { this.followExecutor = followExecutor; }
    @Autowired(required = false)
    public void setChannelRows(ChannelSubscriptionMapper channelRows) { this.channelRows = channelRows; }
    @Autowired(required = false)
    public void setPrecheckDb(JdbcTemplate precheckDb) { this.precheckDb = precheckDb; }

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
    @Transactional(isolation = Isolation.READ_COMMITTED)
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
        if (!q.cacheOnly() && wxPlugin && !WaybillStatus.isTerminal(w.getStatus()) && due(w, now)) {
            changed = allowed.stream().filter(p -> WX.equals(p.channel())).findFirst()
                    .map(p -> probe(w, p, now)).orElse(false);
        }
        if (!q.cacheOnly() && q.refresh() && !allowed.isEmpty() && !WaybillStatus.isTerminal(w.getStatus())) {
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
        LgsWaybill cur = q.cacheOnly() ? w : waybills.selectOne(Wrappers.<LgsWaybill>query()
                .eq("biz_type", q.bizType()).eq("biz_ref", q.bizRef()).last("LIMIT 1 FOR UPDATE"));
        if (cur == null) { return Optional.empty(); }
        List<Node> list = nodes.selectList(Wrappers.<LgsWaybillNode>query()
                        .eq("shipment_no", cur.getShipmentNo()).orderByDesc("at")).stream()
                .map(n -> new Node(n.getAt(), n.getText(), n.getLocation(), n.getLatE6(), n.getLngE6(), n.getStatusCode()))
                .toList();
        boolean plugin = wxPlugin && notBlank(cur.getDisplayToken());
        return Optional.of(new TrackView(cur.getShipmentNo(), cur.getCarrier(), cur.getWaybillNo(), cur.getStatus(),
                cur.getSignedAt(), cur.getAtLocker() != null && cur.getAtLocker() == 1, list,
                plugin ? "wx-plugin" : "self-map", plugin ? cur.getDisplayToken() : null,
                cur.getLastEventAt(), !allowed.isEmpty(),
                cur.getCreatedAt() == null ? null
                        : cur.getCreatedAt().atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()));
    }

    @Override
    public Optional<ShipmentSnapshot> shipmentSnapshotOf(String subOrderNo) {
        LgsWaybill w = find(LgsWaybill.BIZ_SUB_ORDER, subOrderNo);
        if (w == null || w.getSourceShipmentNo() == null || w.getSourceShipmentVersion() == null) {
            return Optional.empty();
        }
        List<LgsWaybillNode> history = nodes.selectList(Wrappers.<LgsWaybillNode>query()
                .eq("shipment_no", w.getShipmentNo()));
        long now = clock.getAsLong();
        Long first = history.stream().map(LgsWaybillNode::getAt)
                .filter(at -> at != null && at > 0 && at <= now).min(Long::compareTo).orElse(null);
        Long signedNode = history.stream().filter(LogisticsPortImpl::signedNode)
                .map(LgsWaybillNode::getAt).filter(at -> at != null && at > 0 && at <= now)
                .max(Long::compareTo).orElse(null);
        // Status-only 微信查询没有签收节点，不能把查询时间冒充实际签收时间。
        Long signed = signedNode == null ? w.getSignedAt() : signedNode;
        return Optional.of(new ShipmentSnapshot(w.getSourceShipmentNo(), w.getSourceShipmentVersion(),
                w.getWaybillNo(), w.getStatus(), signed, signedNode, first));
    }

    private static boolean signedNode(LgsWaybillNode node) {
        String code = node.getStatusCode();
        if (code == null) return false;
        if ("TRACE_SIGNED".equals(code) || "SIGNED".equals(code)) return true;
        if (!"kuaidi100".equals(node.getChannel())) return false;
        try {
            int state = Integer.parseInt(code.trim());
            return state == 3 || state >= 300 && state < 400;
        } catch (NumberFormatException ignored) {
            return false;
        }
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

    @Override
    public Map<String, Long> firstTraceAtOf(Collection<String> subOrderNos) {
        Map<String, Long> out = new HashMap<>();
        if (subOrderNos == null || subOrderNos.isEmpty()) {
            return out;
        }
        for (LgsWaybill w : waybills.selectList(Wrappers.<LgsWaybill>query()
                .eq("biz_type", LgsWaybill.BIZ_SUB_ORDER).in("biz_ref", subOrderNos))) {
            LgsWaybillNode first = nodes.selectOne(Wrappers.<LgsWaybillNode>query()
                    .eq("shipment_no", w.getShipmentNo()).orderByAsc("at").last("LIMIT 1"));
            if (first != null) {
                out.put(w.getBizRef(), first.getAt());
            }
        }
        return out;
    }

    @Override
    public Precheck precheck(String subOrderNo, String carrier, String waybillNo, String receiverPhone) {
        long checkedAt = clock.getAsLong();
        if (carrier == null || waybillNo == null || waybillNo.isBlank()) {
            return new Precheck("INVALID", "LOCAL", null, checkedAt, "快递公司和运单号不能为空", false);
        }
        String number = waybillNo.trim();
        List<LgsWaybill> matches = waybills.selectList(Wrappers.<LgsWaybill>query()
                .eq("carrier", carrier).eq("waybill_no", number)
                .ne("status", WaybillStatus.CANCELLED).orderByDesc("id").last("LIMIT 2"));
        for (LgsWaybill match : matches) {
            if (!Objects.equals(match.getBizRef(), subOrderNo)) {
                boolean finished = WaybillStatus.isTerminal(match.getStatus());
                return new Precheck(finished ? "WARNING" : "INVALID", "LOCAL", match.getStatus(), checkedAt,
                        finished ? "该单号是其他已结束订单的运单，请确认是否复用"
                                : "该单号仍关联其他在途订单，请更换运单号", finished);
            }
        }
        LgsWaybill current = matches.stream().filter(w -> Objects.equals(w.getBizRef(), subOrderNo))
                .findFirst().orElse(null);
        if (current != null && subscriptions != null && follower != null) {
            LgsChannelSubscription row = subscriptions.find(current.getShipmentNo(),
                    ChannelSubscriptionStore.identityKey(current), "wx-follow");
            if (row != null && row.getRef() != null && !row.getRef().isBlank()) {
                Optional<TraceResult> result = follower.queryFollow(row.getRef(), carrier, number);
                if (result.isPresent()) return fromTrace(result.get(), "wx-follow", checkedAt);
            }
        }
        Precheck cached = cachedPrecheck(carrier, number, checkedAt);
        if (cached != null) return cached;
        for (TraceProvider provider : precheckProviders) {
            if (!"kuaidi100".equals(provider.name()) || !provider.available() || !provider.covers(carrier)) continue;
            Optional<TraceResult> result = provider.precheck(carrier, number, receiverPhone);
            Precheck check = result.map(r -> fromTrace(r, provider.name(), checkedAt))
                    .orElseGet(() -> new Precheck("UNKNOWN", provider.name(), null, checkedAt,
                            "渠道尚无可核实的物流信息", false));
            cachePrecheck(carrier, number, check);
            return check;
        }
        return new Precheck("UNKNOWN", null, null, checkedAt, "渠道尚无可核实的物流信息", false);
    }

    private Precheck cachedPrecheck(String carrier, String number, long now) {
        if (precheckDb == null) return null;
        return precheckDb.query("SELECT verdict,status,reason,checked_at FROM lgs_precheck_cache "
                        + "WHERE carrier=? AND waybill_no=? AND checked_at>=?",
                rs -> rs.next() ? new Precheck(rs.getString(1), "kuaidi100", rs.getString(2),
                        rs.getLong(4), rs.getString(3), "WARNING".equals(rs.getString(1))) : null,
                carrier, number, now - 1_800_000L);
    }

    private void cachePrecheck(String carrier, String number, Precheck check) {
        if (precheckDb == null) return;
        int updated = precheckDb.update("UPDATE lgs_precheck_cache SET verdict=?,status=?,reason=?,checked_at=? "
                        + "WHERE carrier=? AND waybill_no=?", check.verdict(), check.status(), check.reason(),
                check.checkedAt(), carrier, number);
        if (updated == 0) {
            try {
                precheckDb.update("INSERT INTO lgs_precheck_cache "
                                + "(carrier,waybill_no,verdict,status,reason,checked_at) VALUES (?,?,?,?,?,?)",
                        carrier, number, check.verdict(), check.status(), check.reason(), check.checkedAt());
            } catch (DuplicateKeyException concurrent) {
                precheckDb.update("UPDATE lgs_precheck_cache SET verdict=?,status=?,reason=?,checked_at=? "
                                + "WHERE carrier=? AND waybill_no=?", check.verdict(), check.status(),
                        check.reason(), check.checkedAt(), carrier, number);
            }
        }
    }

    private static Precheck fromTrace(TraceResult result, String channel, long checkedAt) {
        String status = result.status().name();
        boolean previouslyUsed = result.status() != TraceStatus.UNKNOWN;
        return new Precheck(previouslyUsed ? "WARNING" : "UNKNOWN", channel, status, checkedAt,
                previouslyUsed ? "该单号已有物流状态，请确认这是本次发货的运单" : "渠道尚无物流状态",
                previouslyUsed);
    }

    @Override
    public SyncResult syncAfterShip(String subOrderNo) {
        if (source == null || registrar == null) return new SyncResult("PENDING", null, null, true, "物流同步中");
        try {
            var identity = source.identityOf(subOrderNo).orElse(null);
            if (identity == null) return new SyncResult("PENDING", null, null, true, "发货快照尚未就绪");
            var payload = new LinkedHashMap<String, Object>();
            payload.put("fulfillment", "EXPRESS");
            payload.put("subOrderNo", subOrderNo);
            payload.put("expressNo", identity.waybillNo());
            payload.put("expressCompany", identity.carrier());
            payload.put("sourceShipmentNo", identity.sourceShipmentNo());
            payload.put("sourceShipmentVersion", identity.sourceShipmentVersion());
            SysOutbox immediate = new SysOutbox();
            immediate.setPayload(json.writeValueAsString(payload));
            registrar.consume(immediate);
            LgsWaybill w = find(LgsWaybill.BIZ_SUB_ORDER, subOrderNo);
            if (w == null) return new SyncResult("PENDING", null, null, true, "运单登记中");
            if (followExecutor != null) followExecutor.followNow(w.getShipmentNo());
            return summariesOf(List.of(subOrderNo)).getOrDefault(subOrderNo,
                    new SyncResult("PENDING", null, null, true, "物流同步中"));
        } catch (RuntimeException e) {
            return new SyncResult("PENDING", null, null, true, "发货已提交，物流同步稍后重试");
        }
    }

    @Override
    public Map<String, SyncResult> summariesOf(Collection<String> subOrderNos) {
        if (subOrderNos == null || subOrderNos.isEmpty()) return Map.of();
        List<LgsWaybill> current = waybills.selectList(Wrappers.<LgsWaybill>query()
                .eq("biz_type", LgsWaybill.BIZ_SUB_ORDER).in("biz_ref", subOrderNos));
        if (current.isEmpty()) return Map.of();
        List<String> shipmentNos = current.stream().map(LgsWaybill::getShipmentNo).toList();
        List<LgsChannelSubscription> channels = channelRows == null ? List.of()
                : channelRows.selectList(Wrappers.<LgsChannelSubscription>query().in("shipment_no", shipmentNos));
        Map<String, LgsChannelSubscription> byIdentityChannel = new HashMap<>();
        for (LgsChannelSubscription row : channels) {
            byIdentityChannel.put(row.getShipmentNo() + "|" + row.getIdentityKey() + "|" + row.getChannel(), row);
        }
        Map<String, SyncResult> result = new HashMap<>();
        for (LgsWaybill w : current) {
            String prefix = w.getShipmentNo() + "|" + ChannelSubscriptionStore.identityKey(w) + "|";
            LgsChannelSubscription wx = byIdentityChannel.get(prefix + "wx-follow");
            LgsChannelSubscription kd = byIdentityChannel.get(prefix + "kuaidi100");
            boolean pending = w.getSourceShipmentNo() == null
                    ? !LgsWaybill.SUB_DONE.equals(w.getSubState())
                    : ongoing(wx) || ongoing(kd);
            String reason = wx != null && wx.getError() != null ? wx.getError()
                    : kd != null ? kd.getError() : null;
            String sourceName = w.getStatusSource();
            result.put(w.getBizRef(), new SyncResult(w.getStatus(), sourceName,
                    w.getLastEventAt(), pending, reason));
        }
        return result;
    }

    private static boolean ongoing(LgsChannelSubscription row) {
        return row == null || "PENDING".equals(row.getState()) || "RETRYABLE".equals(row.getState())
                || "WAITING".equals(row.getState());
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
