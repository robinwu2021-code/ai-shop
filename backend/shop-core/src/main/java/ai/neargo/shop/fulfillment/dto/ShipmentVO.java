package ai.neargo.shop.fulfillment.dto;

import java.util.List;

/**
 * 快递运单（P-5.2.1）。
 *
 * @param shipmentNo 平台侧主键，<b>不是快递单号</b>。换单号时它不变，轨迹才连得起来
 * @param orderNo    关联的子订单号（字段名与 ops-web 契约一致）
 * @param carrier    SF / JD / YTO
 * @param status     CREATED / PICKED_UP / IN_TRANSIT / DELIVERED / EXCEPTION。
 *                   <b>EXCEPTION 不是终态</b>：疑难件可能之后又派送成功
 * @param receiver   收件人姓名（下单时快照）
 * @param region     收件地区（省 市）。超区判断看的就是它
 * @param traces     轨迹节点，<b>按时间正序</b>。必填数组：端上直接 {@code .map}，
 *                   不下发会当场抛异常而不是显示为空
 */
public record ShipmentVO(String shipmentNo, String orderNo, String carrier, String waybillNo,
                         String status, String receiver, String region,
                         String createdAt, String updatedAt, List<TraceVO> traces) {

    /**
     * @param at   轨迹时间（ISO-8601）
     * @param text 节点描述，原样来自承运商；平台写的那条会注明来源
     */
    public record TraceVO(String at, String text, String location) {
    }
}
