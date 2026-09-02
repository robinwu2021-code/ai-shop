package ai.neargo.shop.inventory.service;

/**
 * 物料主数据里**商家自己能改的那一小部分**。
 *
 * <p>名字、规格、单位、条码都是从平台商品域投影进来的（{@link InventoryAclService}），
 * <b>本域一律不许改</b> —— 改了下一次投影就会把它盖回去，而中间那段时间两边不一致且不报错。
 * 能改的只有平台那边根本没有的东西，今天只有一样：安全库存阈值。
 */
public interface ItemService {

    /**
     * 设安全库存阈值。低于它算缺货 —— 库存页标红、总览的「缺货」计数、
     * 工作台那张卡的第二个数，读的都是它。
     *
     * <p><b>两级，不是两个功能</b>：物料上是默认值，库位上是覆盖 ——
     * 城西店与仓库的安全线不可能一样，而绝大多数商家只有一个库位、
     * 一辈子只会用到默认值那一级。
     *
     * <p><b>{@code 0} 不是「没设」，是「不预警」</b>。两者在界面上必须长得不一样：
     * 默认全 0 是有意的取值（猜错的预警比没有预警更烦），
     * 显示成「0」的话商家会以为是「低于 0 才报」而去改它。
     *
     * @param locationId 空 = 设物料上的默认值；非空 = 设该库位的覆盖值
     * @param qty        {@code null} <b>只在设库位覆盖时合法</b>，含义是「撤掉覆盖，跟随默认」。
     *                   设默认值时传 null 是错的：物料那一列 {@code NOT NULL}，
     *                   而「撤掉默认值」这件事没有意义
     * @throws ai.neargo.shop.common.BizException {@code NOT_FOUND} 物料不在本业主名下 /
     *                                            指定的库位上这件货没有余额行；
     *                                            {@code BAD_REQUEST} 阈值为负、超上限，或设默认值时传了 null
     */
    void setSafetyStock(String ownerId, String itemId, String locationId, Integer qty, String operator);
}
