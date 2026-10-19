package ai.neargo.shop.logistics.wxbind;

import ai.neargo.shop.event.outbox.RetryableOutboxException;
import ai.neargo.shop.event.outbox.DeliveryResult;
import ai.neargo.shop.event.outbox.OutboxLease;
import ai.neargo.shop.event.OutboxConsumer;
import ai.neargo.shop.event.SysOutbox;
import ai.neargo.shop.logistics.capability.ChannelOutcome;
import ai.neargo.shop.logistics.capability.WaybillTokenBinder;
import ai.neargo.shop.logistics.config.LogisticsProperties;
import ai.neargo.shop.logistics.domain.PhoneCipher;
import ai.neargo.shop.logistics.domain.WaybillSourceIdentity;
import ai.neargo.shop.logistics.entity.LgsWaybill;
import ai.neargo.shop.logistics.mapper.LogisticsMappers.WaybillMapper;
import ai.neargo.shop.logistics.routing.CarrierCodeBook;
import ai.neargo.shop.spi.logistics.LogisticsPort;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.beans.factory.annotation.Autowired;
import ai.neargo.shop.spi.logistics.ShipmentSourcePort;
import tools.jackson.databind.ObjectMapper;

import java.util.List;

/**
 * 去微信换 waybill_token（TDD-物流模块 M6）。消费物流内部事件 {@link WxBindRequested}。
 *
 * <p>结局：成功 → DONE + token；微信还没收录（9300559）→ 保持 WAITING，<b>不重试</b>（下一条推送会再触发）；
 * 手机号错 / openid 与小程序不匹配 → FATAL；取 token 失败 / 超限 / 网络 → 抛给 outbox 退避。
 */
@Component
public class WxBindExecutor implements OutboxConsumer {

    private static final Logger log = LoggerFactory.getLogger(WxBindExecutor.class);

    private final WaybillMapper waybills;
    private final List<WaybillTokenBinder> binders;
    private final CarrierCodeBook codes;
    private final PhoneCipher phones;
    private final LogisticsProperties props;
    private final ObjectMapper json;
    private ShipmentSourcePort source;
    private LogisticsPort logistics;

    @Autowired(required = false)
    public void sourcePort(ShipmentSourcePort source) { this.source = source; }

    @Autowired(required = false)
    public void logisticsPort(LogisticsPort logistics) { this.logistics = logistics; }

    private boolean currentSource(LgsWaybill waybill) {
        return waybill.getSourceShipmentNo() == null || (source != null
                && source.identityOf(waybill.getBizRef())
                .map(identity -> WaybillSourceIdentity.matches(waybill, identity)).orElse(false));
    }


    public WxBindExecutor(WaybillMapper waybills, List<WaybillTokenBinder> binders, CarrierCodeBook codes,
                          PhoneCipher phones, LogisticsProperties props, ObjectMapper json) {
        this.waybills = waybills;
        this.binders = binders;
        this.codes = codes;
        this.phones = phones;
        this.props = props;
        this.json = json;
    }

    @Override
    public String handlerId() { return "logistics-wx-bind"; }

    @Override
    public boolean externalWorkflow() { return true; }

    @Override
    public boolean supports(String eventType) {
        return WxBindRequested.TYPE.equals(eventType);
    }

    @Override
    public DeliveryResult deliver(SysOutbox event) {
        String shipmentNo = json.readTree(event.getPayload()).path("shipmentNo").asString("");
        LgsWaybill before = waybills.selectOne(Wrappers.<LgsWaybill>query().eq("shipment_no", shipmentNo).last("limit 1"));
        if (WxBindPolicy.ready(before, props.getWxBind().onShip())
                && binders.stream().noneMatch(b -> b.available() && props.enabled(b.channel()))) {
            return DeliveryResult.failed("NOT_CONFIGURED", "No token binding channel available");
        }
        consume(event);
        LgsWaybill w = waybills.selectOne(Wrappers.<LgsWaybill>query().eq("shipment_no", shipmentNo).last("limit 1"));
        if (w != null && LgsWaybill.BIND_FATAL.equals(w.getBindState())) {
            return DeliveryResult.failed("BIND_FAILED", "See logistics action state");
        }
        return WxBindPolicy.ready(w, props.getWxBind().onShip())
                ? DeliveryResult.retry("NOT_READY", "Token not available yet") : DeliveryResult.sent();
    }

    @Override
    public void consume(SysOutbox event) {
        String shipmentNo = json.readTree(event.getPayload()).path("shipmentNo").asString("");
        LgsWaybill w = waybills.selectOne(Wrappers.<LgsWaybill>query().eq("shipment_no", shipmentNo).last("limit 1"));
        if (!WxBindPolicy.ready(w, props.getWxBind().onShip()) || !currentSource(w)) {
            return;   // 已换过 / 前置条件又不满足了：幂等
        }
        WaybillTokenBinder binder = binders.stream().filter(b -> b.available() && props.enabled(b.channel()))
                .findFirst().orElse(null);
        if (binder == null) {
            log.warn("[lgs-wxbind] {} 没有可用的换 token 渠道（微信 appid / secret 没配？）", shipmentNo);
            return;
        }
        OutboxLease.check();
        ChannelOutcome r = binder.bind(new WaybillTokenBinder.BindCmd(
                w.getWxOpenid(), w.getWxTransId(), w.getWaybillNo(),
                codes.codeOf(w.getCarrier(), binder.channel()).orElse(null),
                phones.decrypt(w.getReceiverPhoneEnc()), w.getGoodsBrief(),
                "/pages/order/index?orderNo=" + w.getBizRef()));
        if (!currentSource(w)) { return; }
        LgsWaybill p = LgsWaybill.patch(w.getId());
        switch (r.kind()) {
            case OK -> {
                p.setDisplayToken(r.ref());
                p.setBindState(LgsWaybill.BIND_DONE);
                waybills.update(p, WaybillSourceIdentity.unchanged(w)
                        .set("display_channel", "wx-plugin").set("bind_error", null));
                log.info("[lgs-wxbind] {} 换到 token", shipmentNo);
                // 发货提交时还没有 token；拿到后立即校正一次微信状态。已结束的旧运单
                // 可能不会再有订阅推送，否则要等买家打开物流页才会更新 C/B 的缓存。
                // track 的微信 10 分钟闸负责限制重复查询；查询失败不影响已成功的绑定。
                if (logistics != null) {
                    try {
                        logistics.track(LogisticsPort.TrackQuery.subOrder(w.getBizRef(), "MP", false));
                    } catch (RuntimeException e) {
                        log.warn("[lgs-wxbind] {} 绑定后状态校正失败：{}", shipmentNo, e.toString());
                    }
                }
            }
            case NOT_READY -> log.info("[lgs-wxbind] {} 微信还没收录（{}），等下一条推送", shipmentNo, r.code());
            case FATAL -> {
                p.setBindState(LgsWaybill.BIND_FATAL);
                p.setBindError(clip(r.code() + " " + (r.message() == null ? "" : r.message())));
                waybills.update(p, WaybillSourceIdentity.unchanged(w));
                log.error("[lgs-wxbind] {} 换 token 判死：{} {}", shipmentNo, r.code(), r.message());
            }
            case RETRYABLE -> {
                if ("IO".equals(r.code())) { throw new IllegalStateException("Token binding outcome unknown"); }
                throw new RetryableOutboxException("Token binding rejected; retryable");
            }
            default -> throw new IllegalStateException("unreachable");
        }
    }

    private static String clip(String s) {
        return s.length() > 250 ? s.substring(0, 250) : s;
    }
}
