package ai.neargo.shop.trade.service;

import ai.neargo.shop.common.PageData;
import ai.neargo.shop.trade.dto.OrderVO;

/**
 * 平台端全量订单检索（P-4.1.1）。
 *
 * <p>与 C 端 {@link OrderService#list} / B 端 {@link MerchantOrderService#list} 是**三个方法**：
 * 过滤条件完全不同（属主 / 商家 / 无），合成一个再靠参数分支，
 * 就等于把「谁能看什么」交给调用方决定。
 */
public interface PlatformOrderService {

    PageData<OrderVO> search(String status, long page, long size);

    /**
     * 代客下单（P-4.1.4）：老人打电话来，客服替他把单下了。
     *
     * <p>需求梳理见 {@code docs/requirements/代客下单-需求梳理.md}。第一版的四条边界，
     * 每条都是**可回退的保守选择**：
     * <ol>
     *   <li><b>必须落在真实 {@code userNo} 上</b>。运营端 mock 里原先收的是一个自由文本昵称，
     *       照它做出来的会是一张<b>没有主人的订单</b>：用户在 C 端看不到、付不了款，
     *       售后与积分也没有落点</li>
     *   <li><b>不代付款</b>。默认线下支付（当面付给商家），平台不碰这笔钱；
     *       要走线上就落待支付，由用户自己在 App 里付</li>
     *   <li><b>不代用券、不代扣积分</b> —— 那是顾客的资产，客服替他花掉事后说不清；
     *       所以这个命令里<b>根本没有这两个字段</b></li>
     *   <li><b>不代填地址</b>。快递/自送/上门要收货地址，而地址是个人信息、客服也没法核对，
     *       所以只放行「到点自取」那几种履约方式</li>
     * </ol>
     *
     * <p><b>归因照常按顾客解析</b>，不因为「客服代下」就改成平台流量 ——
     * 归因决定佣金档，改了等于让商家为自己带来的客人付平台档佣金。
     *
     * @param operatorNo     操作的客服，落到订单时间线上（顾客与商家都看得见「这单是代下的」）
     * @param idempotencyKey 运营端在**打开表单时**生成的一次性键。同一张表单连点两次只会有一单；
     *                       而顾客真的想再来一单时，那是另一张表单、另一个键 ——
     *                       不能按「内容相同」去重，那会把「同一天买两次同样的菜」吃掉一单
     */
    OrderVO createProxyOrder(ProxyOrderCommand cmd, String operatorNo, String idempotencyKey);

    /**
     * @param userNo      顾客。运营端从人档里取（{@code OpsPersonVO.userNo}）；
     *                    <b>人档里没绑账号的下不了单</b> —— 那种情况要先让他登录一次
     * @param merchantNo  商家。一次只能下一个商家的货：全站按商家拆单（E3）
     * @param fulfillment 履约方式，只接受不需要收货地址的那几种
     * @param pickupNo    自提点（社区自提）；到店自取不需要
     * @param payMode     {@code OFFLINE}（默认，当面付）/ {@code ONLINE}（用户自己在 App 里付）
     * @param reason      为什么代下。<b>必填</b>，落审计与订单时间线
     */
    record ProxyOrderCommand(String userNo, String merchantNo, java.util.List<Item> items,
                             String fulfillment, String pickupNo, String payMode, String reason) {

        public record Item(String skuNo, int qty) {
        }
    }
}
