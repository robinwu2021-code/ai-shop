package ai.neargo.shop.trade.service;

import ai.neargo.shop.common.PageData;
import ai.neargo.shop.trade.dto.OrderVO;

/**
 * B 端商家订单（[API 清单 §3.4]）。
 *
 * <p>与 C 端 {@link OrderService#list} 同粒度（子单），双方谈同一个订单号时不会各说各的。
 * 但**是两个方法而不是加个参数**：C 端按 userNo 过滤、B 端按 merchantNo 过滤，
 * 合成一个方法就要在里面判「现在是谁在调」—— 那正是越权最容易钻的缝。
 */
public interface MerchantOrderService {

    /**
     * 商家订单列表。
     *
     * @param storeNos 只看这些门店的单。
     *
     *                 <p><b>null = 不按门店过滤</b>（主体全部，含多门店之前那些
     *                 没有门店号的历史单）—— 这是**属主**的「全部门店」。
     *
     *                 <p><b>空集合 = 一家店都看不到</b>，fail-closed：
     *                 一个没被授权到任何门店的员工不该看到任何单。
     *                 把空集合当成「不过滤」是这类越权最常见的写法。
     */
    PageData<OrderVO> list(String merchantNo, java.util.Collection<String> storeNos,
                           String status, long page, long size);

    /**
     * 订单详情（商家视角）。
     *
     * <p><b>查不到别家的单时返回「不存在」而不是「无权限」</b>：
     * 后者等于告诉他这个单号是真的 —— 单号是可枚举的，这就成了一个订单探测器。
     *
     * @param storeNo 非空时还要属于这家店。多门店之后店员只被授权到某几家，
     *                只按主体判的话 A 店店员能翻出 B 店的单
     */
    OrderVO detail(String merchantNo, String storeNo, String subOrderNo);

    /**
     * 发货（EXPRESS 履约）。WAIT_FULFILL → FULFILLING，并记下快递单号。
     *
     * <p><b>快递单号必填</b>：没有单号的「已发货」对买家没有任何用处 ——
     * 他既查不到物流，也无法判断该不该等。
     *
     * @param expressNo 快递单号
     */
    OrderVO ship(String merchantNo, String storeNo, String subOrderNo, String expressNo);

    /**
     * 标记送达。FULFILLING → COMPLETED。
     *
     * <p><b>它不是「确认收货」</b>：确认收货是买家的动作（C 端），
     * 这里是商家自己说「我送到了」。两者都能把单推到 COMPLETED，
     * 但责任方不同 —— 纠纷时要能分清是谁点的，所以走各自的入口并各自留痕。
     */
    OrderVO delivered(String merchantNo, String storeNo, String subOrderNo);
}
