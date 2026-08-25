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
     * 确认已收到线下货款 —— 线下支付这条路上<b>唯一推进订单的动作</b>。
     *
     * <p>内部复用 {@code markPaid}：库存转实扣、入会、发分、结算触发、积分实扣
     * <b>全部与线上单同一条路径</b>。不复用的话，这五件事要在这里再写一遍，
     * 而它们中任何一件写漏了都不会立刻报错。
     *
     * <p><b>操作人要留痕</b>：平台不碰这笔钱，出纠纷时平台能提供的只有
     * 「谁在什么时候点的确认」。缺了它，争议就变成两边各执一词。
     *
     * @param operator 操作方。**当前传主体号** —— 与本域既有的 log(…, operatorNo) 同一口径；
     *                 要精确到人得等 BizContext 带上操作员标识，那是另一件事
     */
    OrderVO confirmOfflinePay(String merchantNo, String storeNo, String subOrderNo, String operator);

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
    /**
     * 商家订单列表。
     *
     * @param status       抽象状态（{@code PAID / FULFILLING / COMPLETED / ...}）。
     *                     <b>不再接受 ARRIVED / SHIPPED</b> —— 那是「状态 × 履约」的组合
     * @param fulfillments 想要的履约方式；空 = 不限。与 {@code status} <b>正交</b>：
     *                     商家的「待核销」= {@code FULFILLING} + 自提/到店核销类，
     *                     「已发货」= {@code FULFILLING} + 配送类
     */
    PageData<OrderVO> list(String merchantNo, java.util.Collection<String> storeNos,
                           String status, java.util.List<String> fulfillments,
                           long page, long size);

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
     * <p><b>这四个数分属两个维度，必须传两个作用域</b>：
     * <ul>
     *   <li>{@code toShip} / {@code toDeliver} —— <b>商家视角</b>，按门店。
     *       「我这家店还有多少货要发」</li>
     *   <li>{@code toVerify} / {@code toPick} —— <b>自提点承接方视角</b>，按自提点，
     *       <b>且不限商家</b>。「我这个点上还有多少货要分、要核」——
     *       一个自提点承接多家商家的货（ADR-005），别家的货也是我要分的</li>
     * </ul>
     *
     * <p>合成一个维度算过（按门店算全部四个数），表现是<b>工作台说「待分拣 1」，
     * 点进去分拣单「共 0 件」</b>：那一单的买家选了别家的自提点，
     * 按门店算它属于我，按自提点算它不在我的点上。
     * 商家看到的是「有活，但找不到」。
     *
     * <p><b>一次查询算完</b>，不是发四条 count：工作台是商家每天开的第一屏。
     *
     * @param storeNos  我能碰的门店，空集合 = 一家都没授权 → 前两个数为 0
     * @param pickupNos 我承接的自提点，空 = 不做自提点 → 后两个数为 0（端上本来也不显示这两格）
     */
    TodoCounts todo(String merchantNo, java.util.Collection<String> storeNos,
                    java.util.Collection<String> pickupNos);

    /** 经营数据（B-11.1）。无单时返回 0，**不是报错** —— 新店第一天打开工作台是正常场景。 */
    StatsSummary stats(String merchantNo, java.util.Collection<String> storeNos);

    // ---------------------------------------------------------------- 跨店（B-11.12.5/6）

    /**
     * 按门店分组的经营数据。跨店总览用。
     *
     * <p><b>口径与 {@link #stats} 逐字相同，只是不合并</b> —— 同一次扫描、同一个成交定义、
     * 同一个时间轴。另写一套的话，总览与单店页面迟早给出两个数，
     * 而商家第一句话会是「哪个是真的」。
     *
     * <p><b>一次查完，不逐店循环调 {@link #stats}</b>：那是 N 次全表扫，
     * 而门店数正是这个功能的自变量 —— 店越多越慢，恰好对着最该用它的那批商家。
     *
     * <p>没有单的门店<b>不在返回的 Map 里</b>（不是 0 值行）。调用方按自己的门店列表
     * 取值并兜底 0 —— 这里凭空补出一堆空行，会让「这个主体有哪些店」出现第二个来源。
     *
     * @return storeNo → 这家店的经营数据。{@code store_no} 为空的历史单不计入任何一行
     */
    java.util.Map<String, StatsSummary> statsByStore(String merchantNo,
                                                     java.util.Collection<String> storeNos);

    /**
     * 按门店分组的待办。**只有门店维度的三项**（toShip / toDeliver / toStock），
     * {@code toVerify} 与 {@code toPick} 恒为 0。
     *
     * <p>后两项是<b>自提点</b>维度且不限商家（见 {@link #todo}）——
     * 把它们摆进「门店」那一列，商家会读成「这家店的活」，
     * 而那些单可能属于别家商家、挂在别人的点上。跨店总览里没有它们的位置。
     */
    java.util.Map<String, TodoCounts> todoByStore(String merchantNo,
                                                  java.util.Collection<String> storeNos);

    /**
     * 按门店分组的窗口对比指标。跨店对比用。
     *
     * @param days 回看天数（含今天）。窗口按<b>自然日</b>取，与商家心里的「最近 30 天」一致
     */
    java.util.Map<String, StoreCompare> compareByStore(String merchantNo,
                                                       java.util.Collection<String> storeNos,
                                                       int days);

    /**
     * 一家店在窗口内的对比指标。
     *
     * @param buyers       窗口内下过单的买家数（去重）
     * @param repeatBuyers 其中下过 ≥2 单的
     * @param repeatRate   {@code repeatBuyers / buyers}。<b>分母为 0 时是 0，不是除零也不是 null</b> ——
     *                     一家还没开张的店，「复购率」这一格该显示 0%，
     *                     而不是让整行变成空白或者让接口 500
     */
    record StoreCompare(int orders, long gmvMinor, int buyers, int repeatBuyers, double repeatRate) {

        public static StoreCompare empty() {
            return new StoreCompare(0, 0L, 0, 0, 0d);
        }
    }

    /**
     * @param toShip    待发货（快递履约）—— 按门店
     * @param toDeliver 待自送（商家自送履约）—— 按门店
     * @param toStock   待备货（自提单已付款，货还没送到自提点）—— <b>按门店</b>。
     *                  这是<b>供货方</b>的活：把货备好送到买家选的那个自提点去。
     *                  与 {@code toPick} 是同一批单的两头 ——
     *                  一个是「我要送出去」，一个是「我要在点上分」，
     *                  而买家常常选的是<b>别家</b>的点，两个数因此不相等
     * @param toVerify  待核销（自提已到货，买家还没来取）—— 按自提点，含别家商家的货
     * @param toPick    待分拣（自提单已付款，还没到货点数）—— 按自提点，含别家商家的货
     */
    record TodoCounts(int toShip, int toDeliver, int toStock, int toVerify, int toPick) {
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
     * @param storeNo 按履约门店筛（{@code ord_sub_order.store_no}，ADR-011 双写），为空不筛
     */
    ai.neargo.shop.common.PageData<OpsOrderVO> opsList(String status, String merchantNo,
                                                       String storeNo, String keyword,
                                                       long page, long size);

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
