package ai.neargo.shop.spi.logistics;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 运营端 → 物流（TDD-物流模块 M10 / ADR-032）：运单列表、换单号、重放、渠道总览、承运商编码。
 *
 * <p><b>controller 留在主应用</b>（鉴权、判权、数据域都在那边），这里只收显式参数 ——
 * 物流不装数据域引擎（将来独立成服务）。运营的商家范围由调用方算好传进来：
 * {@code entityNos == null} = 不限；空集 = 一条都看不到（fail-closed，与数据域引擎同口径）。
 * 不在范围里的运单，写操作一律当「不存在」（10404），不区分，防探测。
 */
public interface LogisticsAdminPort {

    String REPLAY_SUBSCRIBE = "SUBSCRIBE";
    String REPLAY_WX_BIND = "WX_BIND";

    /**
     * @param keyword 匹配运单号、子单号
     */
    record ShipmentQuery(String status, String carrier, String keyword, String subState, String bindState,
                         String subChannel, String profile, Set<String> entityNos, long page, long size) {
    }

    record ShipmentPage(List<Shipment> records, long total) {
    }

    /**
     * @param orderNo  子单号（运单的业务键）
     * @param traces   轨迹节点，<b>正序</b>（运营端沿用）
     * @param subError 订阅最后一次失败：渠道 + 码 + 原文
     */
    record Shipment(String shipmentNo, String orderNo, String carrier, String waybillNo, String status,
                    String receiver, String region, LocalDateTime createdAt, LocalDateTime updatedAt,
                    List<Node> traces, String profile, String storeNo, String entityNo,
                    String subState, String subChannel, String subError, Integer subAttempts,
                    String bindState, String bindError, Long signedAt, Long lastEventAt, boolean atLocker,
                    String carrierCorrectedFrom, String receiverPhoneLast4) {
    }

    record Node(long at, String text, String location) {
    }

    ShipmentPage list(ShipmentQuery q);

    /**
     * 换运单号（原地换：运单唯一键在业务单号上，同一子单只有一张运单）。
     * 旧号的订阅与 token 作废、轨迹里记一条「由 X 改为 Y」、按新号重新订阅。
     *
     * @param carrier 可空 = 承运商不变
     * @throws ai.neargo.shop.common.BizException 30006 已签收 / 已作废；30007 单号被占；10400 参数缺
     */
    Shipment changeWaybill(String shipmentNo, String carrier, String waybillNo, String reason, Set<String> entityNos);

    /**
     * 重放：重新订阅（可指定渠道）或重新换微信 token。只改状态、发事件，立即返回；结果看列表。
     *
     * @param action  {@link #REPLAY_SUBSCRIBE} / {@link #REPLAY_WX_BIND}
     * @param channel 只对重新订阅有意义；空 = 重走路由链
     * @throws ai.neargo.shop.common.BizException 30013 / 30014 / 30015 / 10400（线下付款单换 token）
     */
    void replay(String shipmentNo, String action, String channel, Set<String> entityNos);

    List<Channel> channels();

    /**
     * @param capabilities 每种能力：SUBSCRIBE / PUSH / PROBE / BIND
     * @param carriers     覆盖的我方承运商码（来自承运商编码表；全覆盖的渠道写 {@code *}）
     * @param routes       出现在哪些路由链里（{@code subscribe.default#1}、{@code probe.by-store.ST-1#0} …）
     */
    record Channel(String name, boolean enabled, List<Capability> capabilities, List<String> carriers,
                   List<String> routes) {
    }

    /** @param reason 不可用的原因（「配置里没启用」「凭据没配」「推送不可用，订阅随之不可用」） */
    record Capability(String capability, boolean available, String reason) {
    }

    /** 我方承运商码 → {渠道: 渠道编码} */
    Map<String, Map<String, String>> carrierCodes();

    /** 整体替换这家承运商在各渠道的编码（传进来的就是全部；空 map = 清空） */
    void saveCarrierCodes(String carrier, Map<String, String> codes);
}
