package ai.neargo.shop.spi.logistics;

import java.util.List;
import java.util.Optional;

/**
 * 一个**展示渠道**（TDD-物流轨迹多渠道 §2.3）：用什么界面把轨迹呈现给人。
 *
 * <p>与 {@link TraceProvider}（数据源：谁去查轨迹）是**两条正交的轴**，互不知道对方存在 ——
 * 微信插件展示时根本不需要我们查轨迹（微信自己查），自建展示时不关心轨迹是圆通直连还是聚合器查来的。
 *
 * <p>**可并存、按链路由**：全部注册，由 {@code TraceDisplayRouter} 按端的优先级链逐个问
 * {@link #supports}，第一个说「我能呈现这一单」且 {@link #prepare} 成功的胜出；
 * 失败自动落到链上下一个。加第三个渠道（支付宝小程序、承运商 H5…）只要实现本接口 + 配置加一行。
 */
public interface TraceDisplay {

    /** 唯一名字：{@code wx-plugin} / {@code self-map}。配置链里写的就是它，也会下发给端上当 displayMode */
    String name();

    /**
     * 这个端 + 这一单，我能不能呈现。
     *
     * <p><b>链尾那个必须恒真</b>（{@code self-map} 就是），否则某些单会什么都不显示。
     */
    boolean supports(Surface surface, ShipmentCtx ctx);

    /** 备载荷。备不出（外部接口失败、缺前置数据）返回 empty —— **不抛**，让路由落到下一个渠道 */
    Optional<DisplayPayload> prepare(ShipmentCtx ctx);

    /** 呈现的场合。微信插件只在 {@link #MP} 可用 —— 这是微信的限制，不是我们的选择 */
    enum Surface {
        /** C 端小程序 */
        MP,
        /** B 端 App（uni-app 原生包） */
        APP,
        /** 运营端网页 */
        OPS,
        /** H5（B 端调试用） */
        H5
    }

    /**
     * 备载荷要用到的一单的全部上下文。**一次性传齐**，省得各渠道各自回头查库 ——
     * 那会让「加一个渠道」变成「再加一条查询路径」。
     *
     * @param shipmentNo  运单记录号
     * @param carrier     承运商码（微信 delivery_id，如 ZTO）
     * @param waybillNo   运单号
     * @param storeNo     发货门店；数据源那一轴按它路由
     * @param buyerOpenid 买家的小程序 openid。微信插件必需，没有就 supports=false
     * @param transId     微信支付交易单号（4500/420 开头）。微信 trace_waybill 必填
     * @param goodsName   商品名，微信要在落地页显示
     * @param goodsImgUrl 商品图 URL，同上
     * @param orderPath   点商品卡片跳回哪一页（订单详情）
     * @param savedChannel 库里已备好的渠道名，空=还没备过
     * @param savedToken   库里已备好的载荷（微信存 waybill_token）
     */
    record ShipmentCtx(String shipmentNo, String carrier, String waybillNo, String storeNo,
                       String buyerOpenid, String transId, String goodsName, String goodsImgUrl,
                       String orderPath, String savedChannel, String savedToken,
                       /**
                        * 收件人手机号（完整 11 位）。微信 {@code trace_waybill} 的 {@code receiver_phone}：
                        * <b>部分运力（申通 / 中通等）必填</b>，用它查单；顺丰等用 {@code trans_id} 就够。
                        * 缺了这类运单会回 9300561「收件人手机号错误」。可能为空（自提单无收件人）。
                        */
                       String receiverPhone) {

        /** 不带收件人手机号的签名：自建渠道与存量调用方（它们用不到） */
        public ShipmentCtx(String shipmentNo, String carrier, String waybillNo, String storeNo,
                           String buyerOpenid, String transId, String goodsName, String goodsImgUrl,
                           String orderPath, String savedChannel, String savedToken) {
            this(shipmentNo, carrier, waybillNo, storeNo, buyerOpenid, transId, goodsName, goodsImgUrl,
                    orderPath, savedChannel, savedToken, null);
        }
    }

    /**
     * 备好的载荷。端上按 {@code channel} 决定怎么渲染，自己不判断该用哪个渠道。
     *
     * @param channel 渠道名，与 {@link #name()} 一致
     * @param token   该渠道的载荷。微信插件是 waybill_token；自建没有，为 null
     * @param nodes   自建渠道的轨迹节点（倒序）；微信渠道为空 —— 轨迹由微信自己的页面渲染
     * @param route   城市路线「出发 / 当前 / 目的」，自建地图用；取不到为 null
     * @param persist 要不要把 channel+token 落库。微信那种**换取有次数上限**的必须落；自建不用
     */
    record DisplayPayload(String channel, String token, List<TraceResult.TraceNode> nodes,
                          TraceResult.Route route, boolean persist) {
    }
}
