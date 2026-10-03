package ai.neargo.shop.fulfillment.dto;

/**
 * 按自提点汇总的分拣行（P-5.1.2）。<b>只列已签收批次覆盖到的点</b> ——
 * 没签收就分拣，等于把「货到底交没交到点上」这条判据跳过去。
 *
 * <p>与 B 端分拣单（{@code PickingRowVO}）的区别是多了一维**供货商家**：
 * 一个批次混装多家的货，而 B 端只看得到自己那份。
 *
 * @param merchantName 供货商家名（下单时的快照）
 * @param qty          应分拣数量
 * @param shortQty     自提点上报的缺件数。0 与非 0 是两种性质 ——
 *                     非 0 直连售后责任判定，不能都渲染成灰数字
 */
public record SortingRowVO(String pickupNo, String pickupName, String skuNo, String title,
                           String merchantName, int qty, int shortQty) {
}
