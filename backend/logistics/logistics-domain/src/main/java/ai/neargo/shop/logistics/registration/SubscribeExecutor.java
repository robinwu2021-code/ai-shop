package ai.neargo.shop.logistics.registration;

import ai.neargo.shop.event.OutboxConsumer;
import ai.neargo.shop.event.SysOutbox;
import ai.neargo.shop.logistics.capability.ChannelOutcome;
import ai.neargo.shop.logistics.capability.TrackingSubscriber;
import ai.neargo.shop.logistics.config.LogisticsProperties;
import ai.neargo.shop.logistics.domain.PhoneCipher;
import ai.neargo.shop.logistics.domain.WaybillStatus;
import ai.neargo.shop.logistics.entity.LgsWaybill;
import ai.neargo.shop.logistics.event.WaybillRegistered;
import ai.neargo.shop.logistics.mapper.LogisticsMappers.WaybillMapper;
import ai.neargo.shop.logistics.routing.ChannelRouter;
import ai.neargo.shop.spi.logistics.ShipmentSourcePort;
import ai.neargo.shop.spi.logistics.ShipmentSourcePort.ShipmentSource;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Optional;

/**
 * 取登记快照 + 按路由链订阅（TDD-物流模块 M3，AC1 / AC5 / AC7 / AC13）。消费物流内部事件 {@link WaybillRegistered}。
 *
 * <p>结局：
 * <ul>
 *   <li>成功（含快递100 501 重复订阅）→ {@code DONE}，记受理渠道；</li>
 *   <li>可重试 → <b>抛异常</b>，outbox 退避重投（退避、上限、FAILED 终态 outbox 都有，不另写一套）；</li>
 *   <li>不可重试 → 记原因、换链上下一家；链走完 → {@code FATAL}，运营端可见；</li>
 *   <li>累计 {@value #MAX_ATTEMPTS} 次仍不成 → {@code FATAL}：outbox 重试耗尽后补偿作业还会把 PENDING 的再推一遍，
 *       不设上限的话一张一直 500 的单会被两边接力重试到永远。</li>
 * </ul>
 */
@Component
public class SubscribeExecutor implements OutboxConsumer {

    private static final Logger log = LoggerFactory.getLogger(SubscribeExecutor.class);
    static final int MAX_ATTEMPTS = 10;
    /** 快递100：同一公司 + 单号每月最多订阅 4 次（官方文档） */
    static final int KD100_MONTHLY_LIMIT = 4;
    static final String KD100 = "kuaidi100";
    private static final DateTimeFormatter YM = DateTimeFormatter.ofPattern("yyyyMM");

    private final WaybillMapper waybills;
    private final ShipmentSourcePort source;
    private final ChannelRouter router;
    private final PhoneCipher phones;
    private final LogisticsProperties props;
    private final ObjectMapper json;

    public SubscribeExecutor(WaybillMapper waybills, ShipmentSourcePort source, ChannelRouter router,
                             PhoneCipher phones, LogisticsProperties props, ObjectMapper json) {
        this.waybills = waybills;
        this.source = source;
        this.router = router;
        this.phones = phones;
        this.props = props;
        this.json = json;
    }

    @Override
    public boolean supports(String eventType) {
        return WaybillRegistered.TYPE.equals(eventType);
    }

    @Override
    public void consume(SysOutbox event) {
        var payload = json.readTree(event.getPayload());
        String shipmentNo = payload.path("shipmentNo").asString("");
        String forced = payload.path("channel").asString("");
        LgsWaybill w = waybills.selectOne(Wrappers.<LgsWaybill>query().eq("shipment_no", shipmentNo).last("limit 1"));
        if (w == null || !LgsWaybill.SUB_PENDING.equals(w.getSubState())) {
            return;   // 已订上 / 已判死 / 已结束：幂等
        }
        if (WaybillStatus.isTerminal(w.getStatus())) {
            LgsWaybill p = LgsWaybill.patch(w.getId());
            p.setSubState(LgsWaybill.SUB_NA);
            waybills.updateById(p);
            return;
        }
        String phone = snapshot(w);
        if (!props.isSubscribeEnabled()) {
            return;   // 总开关关着：停在 PENDING，打开后补偿作业补订
        }
        subscribe(w, phone, forced.isBlank() ? null : forced);
    }

    /** 取快照写进运单，返回明文手机号（只在内存里用这一次） */
    String snapshot(LgsWaybill w) {
        Optional<ShipmentSource> src = source.sourceOf(w.getBizRef());
        if (src.isEmpty()) {
            log.warn("[lgs-subscribe] {} 取不到发货快照（子单 {}）", w.getShipmentNo(), w.getBizRef());
            return phones.decrypt(w.getReceiverPhoneEnc());
        }
        ShipmentSource s = src.get();
        LgsWaybill p = LgsWaybill.patch(w.getId());
        p.setEntityNo(s.entityNo());
        p.setStoreNo(s.storeNo());
        p.setReceiver(s.receiverName());
        p.setRegion(s.region());
        p.setReceiverPhoneEnc(phones.encrypt(s.receiverPhone()));
        p.setReceiverPhoneLast4(PhoneCipher.last4(s.receiverPhone()));
        if (s.goods() != null && !s.goods().isEmpty()) {
            p.setGoodsBrief(json.writeValueAsString(s.goods().subList(0, Math.min(3, s.goods().size()))));
        }
        if (s.wx() != null && notBlank(s.wx().transId()) && notBlank(s.wx().openid())) {
            p.setProfile(LgsWaybill.PROFILE_WX);
            p.setWxTransId(s.wx().transId());
            p.setWxOpenid(s.wx().openid());
            p.setWxOutTradeNo(s.wx().outTradeNo());
            if (!LgsWaybill.BIND_DONE.equals(w.getBindState())) {
                p.setBindState(LgsWaybill.BIND_WAITING);
            }
        } else {
            p.setProfile(LgsWaybill.PROFILE_SELF);
            p.setBindState(LgsWaybill.BIND_NA);
        }
        if (s.wxUploadedAt() != null && w.getWxUploadedAt() == null) {
            p.setWxUploadedAt(s.wxUploadedAt());   // 上传事件可能先于登记到达，那一次通知落空了，这里补上
        }
        waybills.updateById(p);
        w.setStoreNo(s.storeNo());
        return s.receiverPhone();
    }

    void subscribe(LgsWaybill w, String phone) {
        subscribe(w, phone, null);
    }

    /** @param forced 运营重放时点名的渠道；空 = 走路由链。点名的渠道这时已不可用 → 直接判死，不悄悄换别家 */
    void subscribe(LgsWaybill w, String phone, String forced) {
        int attempts = w.getSubAttempts() == null ? 0 : w.getSubAttempts();
        if (attempts >= MAX_ATTEMPTS) {
            fatal(w, attempts, "累计订阅 " + attempts + " 次仍不成：" + w.getSubError());
            return;
        }
        List<TrackingSubscriber> chain;
        if (forced != null) {
            Optional<String> blocker = router.subscribeBlocker(forced, w.getCarrier());
            if (blocker.isPresent()) {
                fatal(w, attempts, forced + " 不可用：" + blocker.get());
                return;
            }
            chain = router.subscriber(forced).map(List::of).orElse(List.of());
        } else {
            chain = router.subscribers(w.getStoreNo(), w.getCarrier());
        }
        if (chain.isEmpty()) {
            fatal(w, attempts, "没有可用的订阅渠道（承运商 " + w.getCarrier() + "）——看运营端渠道总览");
            return;
        }
        String lastError = null;
        String month = YearMonth.now().format(YM);
        for (TrackingSubscriber ch : chain) {
            if (KD100.equals(ch.channel()) && kd100Count(w, month) >= KD100_MONTHLY_LIMIT) {
                lastError = KD100 + " 本单号本月已订阅 " + KD100_MONTHLY_LIMIT + " 次";
                continue;
            }
            String code = router.codeOf(w.getCarrier(), ch).orElse(null);
            String callback = props.getCallbackBase() + "/" + ch.channel();
            ChannelOutcome r = ch.subscribe(new TrackingSubscriber.SubscribeCmd(
                    w.getCarrier(), code, w.getWaybillNo(), phone, callback));
            attempts++;
            LgsWaybill p = LgsWaybill.patch(w.getId());
            p.setSubAttempts(attempts);
            if (KD100.equals(ch.channel())) {
                p.setKd100SubMonth(month);
                p.setKd100SubCount(kd100Count(w, month) + 1);
            }
            switch (r.kind()) {
                case OK -> {
                    p.setSubState(LgsWaybill.SUB_DONE);
                    p.setSubChannel(ch.channel());
                    p.setSubRef(r.ref());
                    waybills.updateById(p);
                    log.info("[lgs-subscribe] {} {} {} 订阅成功（{}）", w.getShipmentNo(), w.getCarrier(),
                            w.getWaybillNo(), ch.channel());
                    return;
                }
                case RETRYABLE -> {
                    p.setSubError(err(ch, r));
                    waybills.updateById(p);
                    throw new IllegalStateException("订阅可重试失败，交 outbox 退避重投：" + err(ch, r));
                }
                case FATAL -> {
                    lastError = err(ch, r);
                    p.setSubError(lastError);
                    waybills.updateById(p);
                    log.warn("[lgs-subscribe] {} 在 {} 不可重试地失败，换下一家：{}", w.getShipmentNo(), ch.channel(), lastError);
                    w.setKd100SubMonth(p.getKd100SubMonth() != null ? p.getKd100SubMonth() : w.getKd100SubMonth());
                    w.setKd100SubCount(p.getKd100SubCount() != null ? p.getKd100SubCount() : w.getKd100SubCount());
                }
                default -> throw new IllegalStateException("unreachable");
            }
        }
        fatal(w, attempts, lastError);
    }

    private void fatal(LgsWaybill w, int attempts, String reason) {
        LgsWaybill p = LgsWaybill.patch(w.getId());
        p.setSubState(LgsWaybill.SUB_FATAL);
        p.setSubAttempts(attempts);
        p.setSubError(clip(reason));
        waybills.updateById(p);
        log.error("[lgs-subscribe] {} {} {} 订阅判死：{}", w.getShipmentNo(), w.getCarrier(), w.getWaybillNo(), reason);
    }

    private static int kd100Count(LgsWaybill w, String month) {
        return month.equals(w.getKd100SubMonth()) && w.getKd100SubCount() != null ? w.getKd100SubCount() : 0;
    }

    private static String err(TrackingSubscriber ch, ChannelOutcome r) {
        return clip(ch.channel() + " " + r.code() + " " + (r.message() == null ? "" : r.message()));
    }

    private static String clip(String s) {
        return s == null ? null : (s.length() > 250 ? s.substring(0, 250) : s);
    }

    private static boolean notBlank(String s) {
        return s != null && !s.isBlank();
    }
}
