package ai.neargo.shop.logistics.wxbind;

import ai.neargo.shop.event.OutboxConsumer;
import ai.neargo.shop.event.SysOutbox;
import ai.neargo.shop.logistics.capability.ChannelOutcome;
import ai.neargo.shop.logistics.capability.WaybillTokenBinder;
import ai.neargo.shop.logistics.config.LogisticsProperties;
import ai.neargo.shop.logistics.domain.PhoneCipher;
import ai.neargo.shop.logistics.entity.LgsWaybill;
import ai.neargo.shop.logistics.mapper.LogisticsMappers.WaybillMapper;
import ai.neargo.shop.logistics.routing.CarrierCodeBook;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
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
    public boolean supports(String eventType) {
        return WxBindRequested.TYPE.equals(eventType);
    }

    @Override
    public void consume(SysOutbox event) {
        String shipmentNo = json.readTree(event.getPayload()).path("shipmentNo").asString("");
        LgsWaybill w = waybills.selectOne(Wrappers.<LgsWaybill>query().eq("shipment_no", shipmentNo).last("limit 1"));
        if (!WxBindPolicy.ready(w, props.getWxBind().onShip())) {
            return;   // 已换过 / 前置条件又不满足了：幂等
        }
        WaybillTokenBinder binder = binders.stream().filter(b -> b.available() && props.enabled(b.channel()))
                .findFirst().orElse(null);
        if (binder == null) {
            log.warn("[lgs-wxbind] {} 没有可用的换 token 渠道（微信 appid / secret 没配？）", shipmentNo);
            return;
        }
        ChannelOutcome r = binder.bind(new WaybillTokenBinder.BindCmd(
                w.getWxOpenid(), w.getWxTransId(), w.getWaybillNo(),
                codes.codeOf(w.getCarrier(), binder.channel()).orElse(null),
                phones.decrypt(w.getReceiverPhoneEnc()), w.getGoodsBrief(),
                "/pages/order/index?orderNo=" + w.getBizRef()));
        LgsWaybill p = LgsWaybill.patch(w.getId());
        switch (r.kind()) {
            case OK -> {
                p.setDisplayToken(r.ref());
                p.setBindState(LgsWaybill.BIND_DONE);
                waybills.updateById(p);
                // 旧读路径（订单详情）按 display_channel 认渠道：一并写上，过渡期两条路看到的一样
                waybills.update(null, Wrappers.<LgsWaybill>update().set("display_channel", "wx-plugin")
                        .eq("id", w.getId()));
                log.info("[lgs-wxbind] {} 换到 token", shipmentNo);
            }
            case NOT_READY -> log.info("[lgs-wxbind] {} 微信还没收录（{}），等下一条推送", shipmentNo, r.code());
            case FATAL -> {
                p.setBindState(LgsWaybill.BIND_FATAL);
                p.setBindError(clip(r.code() + " " + (r.message() == null ? "" : r.message())));
                waybills.updateById(p);
                log.error("[lgs-wxbind] {} 换 token 判死：{} {}", shipmentNo, r.code(), r.message());
            }
            case RETRYABLE -> throw new IllegalStateException("换 token 可重试失败，交 outbox 退避：" + r.code());
            default -> throw new IllegalStateException("unreachable");
        }
    }

    private static String clip(String s) {
        return s.length() > 250 ? s.substring(0, 250) : s;
    }
}
