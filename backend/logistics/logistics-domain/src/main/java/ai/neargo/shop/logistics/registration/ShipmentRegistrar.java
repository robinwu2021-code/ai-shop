package ai.neargo.shop.logistics.registration;

import ai.neargo.shop.common.BizKey;
import ai.neargo.shop.common.Fulfillments;
import ai.neargo.shop.event.OutboxConsumer;
import ai.neargo.shop.event.OutboxEventBus;
import ai.neargo.shop.event.SysOutbox;
import ai.neargo.shop.logistics.mapper.LogisticsMappers.WaybillNodeMapper;
import ai.neargo.shop.spi.logistics.ShipmentSourcePort;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.annotation.Isolation;
import ai.neargo.shop.logistics.domain.WaybillStatus;
import ai.neargo.shop.logistics.entity.LgsWaybill;
import ai.neargo.shop.logistics.entity.LgsWaybillNode;
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
 * <p>事件冻结来源字段；映射来源用反向 Port 在调用事务持有子单锁，不调用外部渠道。
 * 登记失败沿用原发货事件重投；快照与订阅失败由内部事件独立重试。
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
    private final ShipmentSourcePort source;
    private final WaybillNodeMapper nodes;

    public ShipmentRegistrar(WaybillMapper waybills, OutboxEventBus events, ObjectMapper json,
                             ShipmentSourcePort source, WaybillNodeMapper nodes) {
        this.waybills = waybills;
        this.events = events;
        this.json = json;
        this.source = source;
        this.nodes = nodes;
    }

    @Override
    public boolean supports(String eventType) {
        return SHIPPED.equals(eventType);
    }

    @Override
    @Transactional(isolation = Isolation.READ_COMMITTED)
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
        String sourceNo = text(p, "sourceShipmentNo");
        Long generation = p.path("sourceShipmentVersion").isNumber()
                ? p.path("sourceShipmentVersion").asLong() : null;
        if (sourceNo != null && !current(subOrderNo, sourceNo, generation, carrier, waybillNo)) {
            return;
        }
        LgsWaybill w = findByBizRef(subOrderNo);
        if (w == null) {
            w = skeleton(subOrderNo, carrier, waybillNo);
            w.setSourceShipmentNo(sourceNo);
            w.setSourceShipmentVersion(generation);
            try {
                waybills.insert(w);
            } catch (DuplicateKeyException e) {
                w = findByBizRef(subOrderNo);
            }
        } else if (sourceNo != null && (!sourceNo.equals(w.getSourceShipmentNo())
                || !Objects.equals(generation, w.getSourceShipmentVersion()))) {
            WaybillReplacement.reset(waybills, nodes, w, carrier, waybillNo, sourceNo, generation);
        } else if (sourceNo == null && w.getSourceShipmentNo() == null
                && !Objects.equals(w.getWaybillNo(), waybillNo) && !WaybillStatus.isTerminal(w.getStatus())
                && !hasManualCorrection(w)) {
            // Explicit legacy compatibility; modern mapped events never let legacy replay replace their source.
            WaybillReplacement.reset(waybills, nodes, w, carrier, waybillNo, null, null);
        }
        if (w != null) {
            events.publish(new WaybillRegistered(w.getShipmentNo()));
        }
    }

    private boolean current(String subOrderNo, String sourceNo, Long generation, String carrier, String waybillNo) {
        return source.lockedIdentityOf(subOrderNo).filter(s -> sourceNo.equals(s.sourceShipmentNo())
                && Objects.equals(generation, s.sourceShipmentVersion())
                && Objects.equals(waybillNo, s.waybillNo()) && Objects.equals(carrier, s.carrier())).isPresent();
    }

    private LgsWaybill findByBizRef(String subOrderNo) {
        return waybills.selectOne(Wrappers.<LgsWaybill>query()
                .eq("biz_type", LgsWaybill.BIZ_SUB_ORDER).eq("biz_ref", subOrderNo).last("LIMIT 1 FOR UPDATE"));
    }

    private boolean hasManualCorrection(LgsWaybill waybill) {
        Long audit = nodes.selectCount(Wrappers.<LgsWaybillNode>query()
                .eq("shipment_no", waybill.getShipmentNo()).eq("mode", "OPS"));
        return audit != null && audit > 0;
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
