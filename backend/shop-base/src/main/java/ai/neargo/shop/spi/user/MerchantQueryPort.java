package ai.neargo.shop.spi.user;

import java.util.Optional;

/**
 * trade / fulfillment / settle → user：查商家的最小必要信息。
 *
 * <p>刻意只暴露<b>下单必需</b>的四个字段，而不是返回整个商家实体 ——
 * Port 一旦返回实体，模块边界就名存实亡：调用方会顺手用上不该用的字段，
 * 将来 user 域改一个列，三个模块跟着炸。
 */
public interface MerchantQueryPort {

    /**
     * @param merchantNo 商家业务键
     * @return 空表示商家不存在
     */
    Optional<MerchantBrief> find(String merchantNo);

    /**
     * 批量查 —— 列表页专用（收藏列表、自提点的归属商家、商品卡上的店铺信息）。
     *
     * <p>为什么单独开一个方法而不是让调用方循环 {@link #find(String)}：
     * 这三处都是**一屏一批**的场景，循环即 N+1。而调用方一旦发现单查慢，
     * 下一步就是绕过 Port 直接注入 Mapper 自己批量捞 —— 那正是这次要拆掉的东西。
     * 边界要好用，否则它只会被绕过。
     *
     * @param merchantNos 商家业务键；空集合返回空 Map
     * @return 按 merchantNo 索引；查不到的键**不出现在结果里**（不是 null 值）
     */
    java.util.Map<String, MerchantBrief> findAll(java.util.Collection<String> merchantNos);

    /**
     * 这家店的货<b>能出现在哪些社区</b>（ADR-009，已按 {@code service_scope} 展开）。
     *
     * <p>product 域上架商品时要按这个范围写社区池。放在 Port 上而不是让 product
     * 自己去读 {@code mch_entity_community}：三档范围的展开规则属于 user 域，
     * 两处各实现一遍的结果是「商家页能搜到这家店、商品页却搜不到它的货」。
     *
     * @return 空表示这家店对谁都不可见（scope=COMMUNITY 却一个社区都没配）
     */
    java.util.List<String> reachableCommunities(String merchantNo);

    /**
     * 该主体的<b>默认门店</b>。下单时用它填 {@code ord_sub_order.store_no}（M2 双写）。
     *
     * <p>为什么下单只认默认门店：多门店放开（M6）之前，一个主体恰好一家店，
     * 两者恒等。放开之后这里要换成「按履约方式与用户位置选店」——
     * 那是一次真正的业务决策，不该在双写这一步顺手做掉。
     *
     * @return 空表示该主体没有门店（不该发生，但下单不能因此失败 —— 订单照常创建，
     *         store_no 留空，履约侧按「空 → 默认门店」兜底）
     */
    java.util.Optional<String> defaultStoreNo(String merchantNo);

    /**
     * 这笔钱该打给<b>哪个收款商户号</b>：门店配的号 ?? 主体的默认号。
     *
     * <p><b>只有这一处实现。</b> 两处各写一遍的后果是可预测的：一处按新规则、
     * 一处按老规则，症状是「钱打错账户」—— 而这类错误不会报错，
     * 只会在对账时被发现，还得人工追回。
     *
     * <p>放在 user 域是因为「门店 → 收款号」的归属关系属于商家资料，
     * settle 域不该知道 {@code mch_store} 与 {@code mch_payment_merchant} 长什么样。
     *
     * <p>结算模式不是一个开关，是这个方法的返回值决定的：
     * 两家店解析出同一个号 = 合并结算，解析出不同号 = 分开结算。
     *
     * @param merchantNo 主体业务键
     * @param storeNo    门店业务键；<b>为空按主体默认号解析</b>（存量子单没有门店）
     * @return 空表示这个主体一个可用收款号都没有 —— 进件还没走完，
     *         此时结算单照常生成（钱是欠着的，不是不存在），但不能发起打款
     */
    java.util.Optional<String> payMerchantNoOf(String merchantNo, String storeNo);

    /**
     * @param merchantNo   商家业务键
     * @param merchantName 展示名（下单快照用，商家改名不影响历史订单）
     * @param canSell      是否可上架售卖（审核通过且未封禁）
     * @param canReceive   是否可收款（分账接收方已报备，ADR-002）
     */
    record MerchantBrief(String merchantNo, String merchantName, boolean canSell, boolean canReceive,
                         String logo, double rating, boolean verified, int breachCount) {
    }

    /**
     * 这家店能不能用积分 —— <b>四级串联</b>：全局 → 社区 → 主体非小微 → 本店开关。
     *
     * <p>放在 Port 上而不是让 settle 域自己读四张表：判断顺序本身是有语义的，
     * 主体这一级必须排在商家开关<b>之前</b> —— 小微是「不可开」不是「关着」，
     * 提示语要说「升级为个体工商户后可开启」。两处各实现一遍，
     * 迟早有一处把顺序写反，而那时小微商家会看到「本店未开启积分」，
     * 以为自己打开就行。
     *
     * @return 不可用的原因（<b>直接展示给用户</b>）；可用时返回 {@code null}
     */
    String pointsDenyReason(String merchantNo);

    /** 平台按行业强制开启积分，商家不可自行关闭。 */
    boolean isPointsForced(String merchantNo);

    /**
     * 这家店<b>获批经营哪些类目</b>（{@code mch_entity.category_codes}）。
     *
     * <p>product 域上架商品时，拿它与 {@code prd_category.required_code} 比对。
     * 走 Port 而不是让 product 直接读 {@code mch_entity}：那是跨业务域的直连，
     * ArchUnit 第 1 条就会拦下来 —— 而规则拦的正是这种「为了一个字段捅穿一层边界」。
     *
     * <p><b>空集合表示没有任何特许类目</b>，只能上架无门槛的类目 —— 不是「不限制」。
     * 反过来默认放开的话，卖烧烤的第二天就能上架生鲜，而平台从没校验过。
     */
    java.util.Set<String> authorizedCategoryCodes(String merchantNo);

    /**
     * 某行业下有多少商家。
     *
     * <p>运营改行业准入前要知道影响面 —— 把一个有 300 家店的行业停掉，
     * 和停一个空行业，是两件事。
     */
    long countByIndustry(String industry);
}
