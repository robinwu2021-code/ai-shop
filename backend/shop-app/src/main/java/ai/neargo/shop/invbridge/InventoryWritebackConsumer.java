package ai.neargo.shop.invbridge;

import ai.neargo.shop.event.OutboxConsumer;
import ai.neargo.shop.event.SysOutbox;
import ai.neargo.shop.inventory.config.ConditionalOnInventory;
import ai.neargo.shop.inventory.support.InvEnums;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * 进销存单据过账 → 按规则把商城线上可卖对齐（TDD-商品纳入进销存开关 §6 / §18.3）。
 *
 * <p>事件链是现成的：过账写 {@code inv_outbox(DocumentPosted)}，{@code InvOutboxDispatchJob}
 * 经 {@link PlatformInventoryEventSink} 转进平台 outbox，这里消费。没开同步的门店一行都不动。
 *
 * <p>幂等在商品域（写回明细的唯一键）；镜像没追平时 {@link InventoryWritebackService.MirrorNotCaughtUp}
 * 抛出去，投递器按退避重投 —— 那是这条链的正常路径，不是故障。
 */
@Component
@ConditionalOnInventory
public class InventoryWritebackConsumer implements OutboxConsumer {

    private static final Logger log = LoggerFactory.getLogger(InventoryWritebackConsumer.class);

    /**
     * 平台 outbox 里的类型。**带 {@code INV_} 前缀** —— {@link PlatformInventoryEventSink} 转进来时加的；
     * 按进销存里的原名 {@code DocumentPosted} 匹配的话这个消费者一条都收不到，而且不报错
     */
    static final String EVENT_TYPE = "INV_" + InvEnums.EventType.DOCUMENT_POSTED;

    private final InventoryWritebackService writeback;
    private final ObjectMapper json;

    public InventoryWritebackConsumer(InventoryWritebackService writeback, ObjectMapper json) {
        this.writeback = writeback;
        this.json = json;
    }

    @Override
    public boolean supports(String eventType) {
        return EVENT_TYPE.equals(eventType);
    }

    @Override
    public void consume(SysOutbox event) {
        JsonNode p = json.readTree(event.getPayload());
        /*
         * **单号在内层。** PlatformInventoryEventSink 把进销存的事件包成
         * {@code {ownerId, type, payload:"{\"docNo\":…}"}} —— payload 是一段 JSON 字符串。
         * 直接读外层的 docNo 永远是空，这个消费者会把每条都当「缺单号」跳过，事件照样标 SENT，没有任何报错。
         */
        JsonNode inner = p.get("payload");
        if (inner != null && inner.isString()) {
            p = json.readTree(inner.asString());
        }
        JsonNode docNo = p.get("docNo");
        if (docNo == null || docNo.isNull() || docNo.asString().isBlank()) {
            log.warn("[stock-sync] DocumentPosted 缺 docNo，跳过：{}", event.getEventNo());
            return;
        }
        writeback.onDocumentPosted(docNo.asString());
    }
}
