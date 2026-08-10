package ai.neargo.shop.trade.service;

import ai.neargo.shop.common.PageData;
import ai.neargo.shop.trade.dto.OrderVO;

import java.util.List;

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

    /**
     * 工作台待办计数（B-11.1 首屏）。
     *
     * <p>与 {@link #list} 同一套门店口径：{@code null} = 不过滤（属主的「全部门店」），
     * <b>空集合 = 一家都看不到</b>。把空集合当成不过滤，正是「没被授权的员工反而看到全部」
     * 这类越权最常见的写法。
     *
     * <p><b>一次查询算完四个数</b>，不是发四条 count：工作台是商家每天开的第一屏，
     * 而这四个数天然来自同一批待履约的单。
     */
    TodoCounts todo(String merchantNo, java.util.Collection<String> storeNos);

    /** 经营数据（B-11.1）。无单时返回 0，**不是报错** —— 新店第一天打开工作台是正常场景。 */
    StatsSummary stats(String merchantNo, java.util.Collection<String> storeNos);

    /**
     * @param toShip    待发货（快递履约）
     * @param toDeliver 待自送（商家自送履约）
     * @param toVerify  待核销（自提已到货，买家还没来取）
     * @param toPick    待分拣（自提单已付款，还没到货点数）
     */
    record TodoCounts(int toShip, int toDeliver, int toVerify, int toPick) {
    }

    /**
     * @param ownedTrafficRate 自带客流占比 0–1（{@code traffic_source=MERCHANT_OWNED}）。
     *                         它决定费率档（ADR-004 §6），所以分母是**全部有归因的单**，
     *                         不是全部单 —— 早于归因上线的历史单不该把商家的比例冲低
     */
    record StatsSummary(int todayOrders, long todayGmvMinor, int monthOrders, long monthGmvMinor,
                        double ownedTrafficRate) {
    }

    /**
     * 顾客列表（B-11.10）：按买家聚合本店的订单。
     *
     * <p><b>不下发完整手机号</b>（B12）—— 商家需要的是「认得出是谁、他多久没来了」，
     * 不是能直接打过去。完整号码一旦下发，导出一次就永久离开了平台。
     */
    List<CustomerSummary> customers(String merchantNo, java.util.Collection<String> storeNos);

    /**
     * @param silent          沉默客户：曾经常来、最近没来。**店主唯一能立刻行动的信号**
     * @param source          客流来源：他是商家自己带来的，还是平台分配的
     * @param daysSinceLast   距上次下单天数
     */
    record CustomerSummary(String userNo, String nickname, String avatar,
                           int orderCount, long totalSpentMinor, long lastOrderAt,
                           int daysSinceLast, boolean silent, String source) {
    }

    // ---------------------------------------------------------------- 平台端（P-4.1）

    /**
     * 平台侧订单列表。**跨商家**，客服处理任何问题的第一站。
     *
     * @param keyword 订单号 / 买家昵称，模糊匹配
     */
    ai.neargo.shop.common.PageData<OpsOrderVO> opsList(String status, String merchantNo,
                                                       String keyword, long page, long size);

    /** 平台侧订单详情。 */
    OpsOrderVO opsDetail(String subOrderNo);

    /** 同一次结算拆出的兄弟单 —— 客服要知道「他这一单还买了别家什么」。 */
    java.util.List<OpsOrderVO> siblings(String parentNo);

    /**
     * 异常单队列。<b>实时算出来的视图，不落表</b>。
     *
     * <p>落成记录就会过期：订单已经推进了，异常记录还挂在那里，
     * 运营会去处理一个不存在的问题。
     *
     * <p>阈值**按状态分别给**：待支付 15 分钟就该关单，而「已到自提点待取」
     * 放一天很正常 —— 一刀切会把正常单刷进异常队列，队列一旦变噪音就没人看了。
     */
    java.util.List<OrderExceptionVO> exceptions();

    /** 某单的人工干预留痕。 */
    java.util.List<InterventionVO> interventions(String subOrderNo);

    /**
     * 人工干预订单状态。<b>必须写原因</b> —— 改状态这件事事后要说得清是谁、为什么。
     *
     * <p>迁移由后端状态机判定，不采信端上那份 {@code ORDER_TRANSITIONS}：
     * 那份表只是让界面提前灰掉不可能的选项。
     */
    OpsOrderVO intervene(String subOrderNo, String to, String remark, String operatorNo);

    /**
     * @param communityNo 社区在**主单**上（{@code ord_order.community_no}），子单没有 ——
     *                    运营按社区做数据域隔离，所以要 join 出来
     * @param statusAt    进入当前状态的时刻。异常单「卡了多久」从这里算，不是从下单时间算
     */
    record OpsOrderVO(String orderNo, String parentNo, String status, String merchantNo,
                      String merchantName, String communityNo, String pickupNo,
                      String fulfillType, String trafficSource, String buyerNickname,
                      java.util.List<ItemVO> items, long payAmount,
                      long createdAt, Long paidAt, long statusAt) {

        public record ItemVO(String skuNo, String title, int qty, long price) {
        }
    }

    /**
     * @param kind             STUCK（卡在某状态超时）/ PAY_TIMEOUT（待支付超时未关单）
     * @param thresholdMinutes 该状态允许卡多久 —— 界面要能说明「为什么它算异常」
     */
    record OrderExceptionVO(OpsOrderVO order, String kind, long stuckMinutes, long thresholdMinutes) {
    }

    record InterventionVO(String orderNo, String from, String to, String remark,
                          String operator, long at) {
    }
}
