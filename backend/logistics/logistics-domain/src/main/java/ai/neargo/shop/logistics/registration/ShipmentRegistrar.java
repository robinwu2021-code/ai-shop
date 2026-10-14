package ai.neargo.shop.logistics.registration;

import ai.neargo.shop.common.BizKey;
import ai.neargo.shop.common.Fulfillments;
import ai.neargo.shop.event.OutboxConsumer;
import ai.neargo.shop.event.OutboxEventBus;
import ai.neargo.shop.event.SysOutbox;
import ai.neargo.shop.logistics.domain.WaybillStatus;
import ai.neargo.shop.logistics.entity.LgsWaybill;
import ai.neargo.shop.logistics.event.WaybillRegistered;
import ai.neargo.shop.logistics.mapper.LogisticsMappers.WaybillMapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.time.LocalDateTime;
import java.util.Objects;

/**
 * 发货 → 登记运单骨架（TDD-物流模块 M1，AC1）。<b>取代「物流去扫订单表、读时补齐」</b>。
 *
 * <p>只用事件里已有的字段（子单号、快递公司、单号），<b>不调外部、不调反向 Port</b> ——
 * 这一步失败会让整条发货事件重投，连带通知模块把「已发货」再推一次（见 {@link WaybillRegistered}）。
 * 快照与订阅在 {@link SubscribeExecutor} 上做。
 *
 * <p>幂等靠唯一键 {@code uk_shipment_sub_order(biz_ref)}，不靠查重（查与插之间有并发窗口）：
 * 撞键 = 已登记（重复事件、或过渡期旧的读时补齐先建了），照样发内部事件让快照补齐。
 */
@Component
public class ShipmentRegistrar implements OutboxConsumer {

    private static final Logger log = LoggerFactory.getLogger(ShipmentRegistrar.class);
    static final String SHIPPED = "SUB_ORDER_SHIPPED";

    private final WaybillMapper waybills;
    private final OutboxEventBus events;
    private final ObjectMapper json;

    public ShipmentRegistrar(WaybillMapper waybills, OutboxEventBus events, ObjectMapper json) {
        this.waybills = waybills;
        this.events = events;
        this.json = json;
    }

    @Override
    public boolean supports(String eventType) {
        return SHIPPED.equals(eventType);
    }

    @Override
    public void consume(SysOutbox event) {
        JsonNode p = json.readTree(event.getPayload());
        if (!Fulfillments.EXPRESS.equals(text(p, "fulfillment"))) {
            return;   // 自提 / 自送 / 虚拟不是物流的事
        }
        String subOrderNo = text(p, "subOrderNo");
        String waybillNo = text(p, "expressNo");
        String carrier = text(p, "expressCompany");
        if (subOrderNo == null || waybillNo == null) {
            log.warn("[lgs-register] 快递发货事件缺子单号或单号，不登记：{}", event.getEventNo());
            return;
        }
        LgsWaybill w = findByBizRef(subOrderNo);
        if (w == null) {
            w = skeleton(subOrderNo, carrier, waybillNo);
            try {
                waybills.insert(w);
            } catch (DuplicateKeyException e) {
                w = findByBizRef(subOrderNo);
            }
        } else if (!Objects.equals(w.getWaybillNo(), waybillNo) && !WaybillStatus.isTerminal(w.getStatus())) {
            // 同一子单换了单号（商家重新发货）：按新号重新订阅
            log.warn("[lgs-register] 子单 {} 单号由 {} 换成 {}，按新号重新订阅", subOrderNo, w.getWaybillNo(), waybillNo);
            LgsWaybill patch = LgsWaybill.patch(w.getId());
            patch.setWaybillNo(waybillNo);
            patch.setCarrier(carrier);
            patch.setSubState(LgsWaybill.SUB_PENDING);
            waybills.updateById(patch);
        }
        if (w != null) {
            events.publish(new WaybillRegistered(w.getShipmentNo()));
        }
    }

    private LgsWaybill findByBizRef(String subOrderNo) {
        return waybills.selectOne(Wrappers.<LgsWaybill>query()
                .eq("biz_type", LgsWaybill.BIZ_SUB_ORDER).eq("biz_ref", subOrderNo).last("limit 1"));
    }

    private static LgsWaybill skeleton(String subOrderNo, String carrier, String waybillNo) {
        LgsWaybill w = new LgsWaybill();
        w.setShipmentNo(BizKey.next(BizKey.SHIPMENT));
        w.setBizType(LgsWaybill.BIZ_SUB_ORDER);
        w.setBizRef(subOrderNo);
        w.setCarrier(carrier == null || carrier.isBlank() ? "UNKNOWN" : carrier);
        w.setWaybillNo(waybillNo);
        w.setStatus(WaybillStatus.CREATED);
        w.setProfile(LgsWaybill.PROFILE_SELF);   // 快照到了再定；先按最保守的（不调微信）
        w.setSubState(LgsWaybill.SUB_PENDING);
        w.setSubAttempts(0);
        w.setKd100SubCount(0);
        w.setBindState(LgsWaybill.BIND_NA);
        w.setAtLocker(0);
        w.setTenantNo("MAIN");
        w.setCreatedAt(LocalDateTime.now());
        w.setUpdatedAt(LocalDateTime.now());
        w.setCreatedBy("SYSTEM");
        w.setUpdatedBy("SYSTEM");
        w.setVersion(0L);
        w.setDeleted(0);
        return w;
    }

    private static String text(JsonNode n, String field) {
        JsonNode v = n.get(field);
        if (v == null || v.isNull()) {
            return null;
        }
        String s = v.asString("").trim();
        return s.isEmpty() ? null : s;
    }
}
