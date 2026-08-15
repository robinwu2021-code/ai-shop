package ai.neargo.shop.fulfillment.dto;

/**
 * 到货批次与配车（P-5.1.1）。平台视角：一个自提点某一天到的一堆货。
 *
 * @param status        PLANNED / DISPATCHED / ARRIVED / SIGNED，<b>有序推进不许跳步</b>
 * @param communityName 社区名快照。看板上按名字读，只给号的话运营要自己翻对照表
 * @param planArriveAt  计划到货时间（ISO-8601）
 * @param vehicle       车次/司机。一期人肉填，未派车时是「待派」
 * @param itemCount     本批件数。<b>现算自订单，不是存下来的计数器</b> ——
 *                      存一份的代价是「总览说 3 单、点进去只有 2 单」
 * @param merchantCount 涉及的商家数。跨商家拆单后一个批次会混装多家的货，
 *                      分拣工作量按它预判 —— 这也正是平台视角存在的理由
 */
public record ArrivalBatchVO(String batchNo, String status,
                             String communityNo, String communityName,
                             String pickupNo, String pickupName,
                             String planArriveAt, String vehicle,
                             int itemCount, int merchantCount) {
}
