package ai.neargo.shop.logistics.port;

import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.event.OutboxEventBus;
import ai.neargo.shop.logistics.capability.ChannelCapability;
import ai.neargo.shop.logistics.capability.PushReceiver;
import ai.neargo.shop.logistics.capability.StatusProbe;
import ai.neargo.shop.logistics.capability.TrackingSubscriber;
import ai.neargo.shop.logistics.capability.WaybillTokenBinder;
import ai.neargo.shop.logistics.config.LogisticsProperties;
import ai.neargo.shop.logistics.domain.WaybillStatus;
import ai.neargo.shop.logistics.entity.LgsCarrierCode;
import ai.neargo.shop.logistics.entity.LgsWaybill;
import ai.neargo.shop.logistics.entity.LgsWaybillNode;
import ai.neargo.shop.logistics.event.WaybillRegistered;
import ai.neargo.shop.logistics.mapper.LogisticsMappers.CarrierCodeMapper;
import ai.neargo.shop.logistics.mapper.LogisticsMappers.WaybillMapper;
import ai.neargo.shop.logistics.mapper.LogisticsMappers.WaybillNodeMapper;
import ai.neargo.shop.logistics.routing.CarrierCodeBook;
import ai.neargo.shop.logistics.routing.ChannelRouter;
import ai.neargo.shop.logistics.wxbind.WxBindRequested;
import ai.neargo.shop.spi.logistics.LogisticsAdminPort;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;

/**
 * 运营端 → 物流（TDD-物流模块 M10）。controller 在主应用 {@code portal/ops}，判权与数据域在那边。
 */
@Component
public class LogisticsAdminPortImpl implements LogisticsAdminPort {

    static final String KD100 = "kuaidi100";
    static final int KD100_MONTHLY_LIMIT = 4;
    private static final DateTimeFormatter YM = DateTimeFormatter.ofPattern("yyyyMM");

    private final WaybillMapper waybills;
    private final WaybillNodeMapper nodes;
    private final CarrierCodeMapper codeMapper;
    private final CarrierCodeBook codes;
    private final ChannelRouter router;
    private final List<WaybillTokenBinder> binders;
    private final LogisticsProperties props;
    private final OutboxEventBus events;

    public LogisticsAdminPortImpl(WaybillMapper waybills, WaybillNodeMapper nodes, CarrierCodeMapper codeMapper,
                                  CarrierCodeBook codes, ChannelRouter router, List<WaybillTokenBinder> binders,
                                  LogisticsProperties props, OutboxEventBus events) {
        this.waybills = waybills;
        this.nodes = nodes;
        this.codeMapper = codeMapper;
        this.codes = codes;
        this.router = router;
        this.binders = binders;
        this.props = props;
        this.events = events;
    }

    // ---------------------------------------------------------------- O1 列表

    @Override
    public ShipmentPage list(ShipmentQuery q) {
        if (q.entityNos() != null && q.entityNos().isEmpty()) {
            return new ShipmentPage(List.of(), 0);   // 配了范围却一个商家都没有：看不到，不是看全部
        }
        QueryWrapper<LgsWaybill> w = Wrappers.query();
        if (q.entityNos() != null) {
            w.in("entity_no", q.entityNos());
        }
        eq(w, "status", q.status());
        eq(w, "carrier", q.carrier());
        eq(w, "sub_state", q.subState());
        eq(w, "bind_state", q.bindState());
        eq(w, "sub_channel", q.subChannel());
        eq(w, "profile", q.profile());
        if (notBlank(q.keyword())) {
            String kw = q.keyword().trim();
            w.and(x -> x.like("waybill_no", kw).or().like("biz_ref", kw).or().like("receiver", kw));
        }
        w.orderByDesc("id");
        Page<LgsWaybill> page = waybills.selectPage(Page.of(Math.max(1, q.page()), Math.max(1, q.size())), w);
        Map<String, List<Node>> traces = tracesOf(page.getRecords().stream().map(LgsWaybill::getShipmentNo).toList());
        List<Shipment> rows = page.getRecords().stream()
                .map(r -> toShipment(r, traces.getOrDefault(r.getShipmentNo(), List.of()))).toList();
        return new ShipmentPage(rows, page.getTotal());
    }

    // ---------------------------------------------------------------- O2 换单号

    @Override
    @Transactional
    public Shipment changeWaybill(String shipmentNo, String carrier, String waybillNo, String reason,
                                  Set<String> entityNos) {
        // 原因必填：之后对不上时这是唯一线索，而用户可能已拿着旧号在查件
        if (!notBlank(waybillNo) || !notBlank(reason)) {
            throw BizException.of(ErrorCode.BAD_REQUEST);
        }
        LgsWaybill w = require(shipmentNo, entityNos);
        if (WaybillStatus.isTerminal(w.getStatus())) {
            throw BizException.of(ErrorCode.WAYBILL_LOCKED);   // 等于把一条已完成的轨迹指向别处
        }
        String newCarrier = notBlank(carrier) ? carrier.trim() : w.getCarrier();
        String newNo = waybillNo.trim();
        Long dup = waybills.selectCount(Wrappers.<LgsWaybill>query()
                .eq("carrier", newCarrier).eq("waybill_no", newNo).ne("shipment_no", shipmentNo));
        if (dup != null && dup > 0) {
            throw BizException.of(ErrorCode.WAYBILL_DUPLICATED);   // 两单的轨迹会搅在一起
        }
        /*
         * 原地换：运单唯一键在业务单号上，同一子单只有一张运单（V387 的说明）。
         * 旧号上的一切都作废 —— 订阅、token、到柜、签收都是旧号的事实；
         * 状态回到 CREATED 是运营动作，不走「只进不退」（那条规矩管的是渠道推来的进展）。
         * 清空要显式 set null：updateById 跳过 null。
         */
        boolean wx = LgsWaybill.PROFILE_WX.equals(w.getProfile());
        waybills.update(null, Wrappers.<LgsWaybill>update()
                .set("carrier", newCarrier).set("waybill_no", newNo)
                .set("status", WaybillStatus.CREATED)
                .set("sub_state", LgsWaybill.SUB_PENDING).set("sub_channel", null).set("sub_ref", null)
                .set("sub_attempts", 0).set("sub_error", null)
                .set("kd100_sub_month", null).set("kd100_sub_count", 0)
                .set("bind_state", wx ? LgsWaybill.BIND_WAITING : LgsWaybill.BIND_NA)
                .set("bind_error", null).set("display_token", null).set("wx_status_checked_at", null)
                .set("picked_up_at", null).set("signed_at", null).set("at_locker", 0)
                .set("carrier_corrected_from", null)
                .set("updated_at", LocalDateTime.now())
                .eq("id", w.getId()));
        // 换号本身要进轨迹：不写的话，之后看到的是一条凭空换了单号的运单
        LgsWaybillNode n = new LgsWaybillNode();
        n.setShipmentNo(shipmentNo);
        n.setAt(System.currentTimeMillis());
        n.setText("运单号由 " + (w.getWaybillNo() == null ? "空" : w.getWaybillNo()) + " 改为 " + newNo
                + (newCarrier.equals(w.getCarrier()) ? "" : "（承运商 " + w.getCarrier() + " → " + newCarrier + "）")
                + "：" + reason.trim());
        n.setChannel("ops");
        n.setMode("OPS");
        n.setTenantNo("MAIN");
        n.setCreatedAt(LocalDateTime.now());
        nodes.insert(n);
        events.publish(new WaybillRegistered(shipmentNo));   // 按新号重新订阅（开关关着时停在 PENDING）
        LgsWaybill after = waybills.selectById(w.getId());
        return toShipment(after, tracesOf(List.of(shipmentNo)).getOrDefault(shipmentNo, List.of()));
    }

    // ---------------------------------------------------------------- O3 重放

    @Override
    @Transactional
    public void replay(String shipmentNo, String action, String channel, Set<String> entityNos) {
        LgsWaybill w = require(shipmentNo, entityNos);
        if (WaybillStatus.isTerminal(w.getStatus())) {
            throw BizException.of(ErrorCode.WAYBILL_TERMINAL);
        }
        if (REPLAY_WX_BIND.equals(action)) {
            if (!LgsWaybill.PROFILE_WX.equals(w.getProfile())) {
                throw BizException.of(ErrorCode.BAD_REQUEST);   // 线下付款单不调任何微信物流接口（AC5）
            }
            waybills.update(null, Wrappers.<LgsWaybill>update()
                    .set("bind_state", LgsWaybill.BIND_WAITING).set("bind_error", null)
                    .set("updated_at", LocalDateTime.now()).eq("id", w.getId()));
            events.publish(new WxBindRequested(shipmentNo));
            return;
        }
        if (!REPLAY_SUBSCRIBE.equals(action)) {
            throw BizException.of(ErrorCode.BAD_REQUEST);
        }
        String forced = notBlank(channel) ? channel.trim() : null;
        if (forced != null) {
            Optional<String> blocker = router.subscribeBlocker(forced, w.getCarrier());
            if (blocker.isPresent()) {
                throw BizException.of(ErrorCode.LOGISTICS_CHANNEL_UNAVAILABLE, blocker.get());
            }
        }
        boolean viaKd100 = KD100.equals(forced)
                || (forced == null && router.subscribers(w.getStoreNo(), w.getCarrier()).stream()
                .allMatch(s -> KD100.equals(s.channel())));
        if (viaKd100 && kd100UsedUp(w)) {
            throw BizException.of(ErrorCode.WAYBILL_SUBSCRIBE_LIMIT);
        }
        waybills.update(null, Wrappers.<LgsWaybill>update()
                .set("sub_state", LgsWaybill.SUB_PENDING).set("sub_attempts", 0).set("sub_error", null)
                .set("updated_at", LocalDateTime.now()).eq("id", w.getId()));
        events.publish(new WaybillRegistered(shipmentNo, forced));
    }

    private static boolean kd100UsedUp(LgsWaybill w) {
        String month = YearMonth.now().format(YM);
        return month.equals(w.getKd100SubMonth()) && w.getKd100SubCount() != null
                && w.getKd100SubCount() >= KD100_MONTHLY_LIMIT;
    }

    // ---------------------------------------------------------------- O4 渠道总览

    @Override
    public List<Channel> channels() {
        Map<String, List<Capability>> caps = new TreeMap<>();
        for (TrackingSubscriber s : router.installedSubscribers().values()) {
            Optional<String> r = router.blocker(s).or(() ->
                    router.installedReceivers().containsKey(s.channel())
                            && router.blocker(router.installedReceivers().get(s.channel())).isEmpty()
                            ? Optional.empty() : Optional.of("推送不可用，订阅随之不可用"));
            caps.computeIfAbsent(s.channel(), k -> new ArrayList<>()).add(new Capability("SUBSCRIBE", r.isEmpty(), r.orElse(null)));
        }
        add(caps, "PUSH", router.installedReceivers().values());
        add(caps, "PROBE", router.installedProbes().values());
        add(caps, "BIND", binders);
        List<Channel> out = new ArrayList<>();
        for (var e : caps.entrySet()) {
            String name = e.getKey();
            boolean coversAll = coversAll(name);
            out.add(new Channel(name, props.enabled(name), e.getValue(),
                    coversAll ? List.of("*") : codes.carriersOf(name), routesOf(name)));
        }
        return out;
    }

    private void add(Map<String, List<Capability>> caps, String capability, java.util.Collection<? extends ChannelCapability> list) {
        for (ChannelCapability c : list) {
            Optional<String> r = router.blocker(c);
            caps.computeIfAbsent(c.channel(), k -> new ArrayList<>()).add(new Capability(capability, r.isEmpty(), r.orElse(null)));
        }
    }

    private boolean coversAll(String channel) {
        return router.installedSubscribers().values().stream().anyMatch(s -> s.channel().equals(channel) && s.coversAllCarriers())
                || router.installedProbes().values().stream().anyMatch(s -> s.channel().equals(channel) && s.coversAllCarriers())
                || router.installedReceivers().values().stream().anyMatch(s -> s.channel().equals(channel) && s.coversAllCarriers());
    }

    private List<String> routesOf(String channel) {
        List<String> out = new ArrayList<>();
        routes(out, "subscribe", props.getRoutes().getSubscribe(), channel);
        routes(out, "probe", props.getRoutes().getProbe(), channel);
        return out;
    }

    private static void routes(List<String> out, String what, LogisticsProperties.Chain chain, String channel) {
        idx(out, what + ".default", chain.getByDefault(), channel);
        chain.getByCarrier().forEach((k, v) -> idx(out, what + ".by-carrier." + k, v, channel));
        chain.getByStore().forEach((k, v) -> idx(out, what + ".by-store." + k, v, channel));
    }

    private static void idx(List<String> out, String key, List<String> names, String channel) {
        int i = names.indexOf(channel);
        if (i >= 0) {
            out.add(key + "#" + i);
        }
    }

    // ---------------------------------------------------------------- O5 承运商编码

    @Override
    public Map<String, Map<String, String>> carrierCodes() {
        Map<String, Map<String, String>> out = new TreeMap<>();
        for (LgsCarrierCode c : codeMapper.selectList(Wrappers.<LgsCarrierCode>query().orderByAsc("carrier", "channel"))) {
            out.computeIfAbsent(c.getCarrier(), k -> new LinkedHashMap<>()).put(c.getChannel(), c.getCode());
        }
        return out;
    }

    @Override
    @Transactional
    public void saveCarrierCodes(String carrier, Map<String, String> next) {
        codeMapper.delete(Wrappers.<LgsCarrierCode>query().eq("carrier", carrier));
        for (var e : next.entrySet()) {
            if (!notBlank(e.getKey()) || !notBlank(e.getValue())) {
                continue;
            }
            LgsCarrierCode c = new LgsCarrierCode();
            c.setCarrier(carrier);
            c.setChannel(e.getKey().trim());
            c.setCode(e.getValue().trim());
            c.setCreatedAt(LocalDateTime.now());
            c.setUpdatedAt(LocalDateTime.now());
            codeMapper.insert(c);
        }
        codes.invalidate();   // 编码有进程内缓存：不清的话改了要等重启才生效
    }

    // ---------------------------------------------------------------- 共用

    /** 找不到与不在范围里不区分，都是 10404（防探测） */
    private LgsWaybill require(String shipmentNo, Set<String> entityNos) {
        LgsWaybill w = waybills.selectOne(Wrappers.<LgsWaybill>query().eq("shipment_no", shipmentNo).last("limit 1"));
        if (w == null || (entityNos != null && (w.getEntityNo() == null || !entityNos.contains(w.getEntityNo())))) {
            throw BizException.of(ErrorCode.NOT_FOUND);
        }
        return w;
    }

    private Map<String, List<Node>> tracesOf(List<String> shipmentNos) {
        Map<String, List<Node>> out = new LinkedHashMap<>();
        if (shipmentNos.isEmpty()) {
            return out;
        }
        for (LgsWaybillNode n : nodes.selectList(Wrappers.<LgsWaybillNode>query()
                .in("shipment_no", shipmentNos).orderByAsc("at"))) {
            out.computeIfAbsent(n.getShipmentNo(), k -> new ArrayList<>())
                    .add(new Node(n.getAt() == null ? 0 : n.getAt(), n.getText(), n.getLocation()));
        }
        return out;
    }

    private static Shipment toShipment(LgsWaybill w, List<Node> traces) {
        return new Shipment(w.getShipmentNo(), w.getBizRef(), w.getCarrier(), w.getWaybillNo(), w.getStatus(),
                w.getReceiver(), w.getRegion(), w.getCreatedAt(), w.getUpdatedAt(), traces,
                w.getProfile(), w.getStoreNo(), w.getEntityNo(),
                w.getSubState(), w.getSubChannel(), w.getSubError(), w.getSubAttempts(),
                w.getBindState(), w.getBindError(), w.getSignedAt(), w.getLastEventAt(),
                w.getAtLocker() != null && w.getAtLocker() == 1,
                w.getCarrierCorrectedFrom(), w.getReceiverPhoneLast4());
    }

    private static void eq(QueryWrapper<LgsWaybill> w, String col, String v) {
        if (notBlank(v)) {
            w.eq(col, v.trim());
        }
    }

    private static boolean notBlank(String s) {
        return s != null && !s.isBlank();
    }
}
