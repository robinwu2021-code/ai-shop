package ai.neargo.shop.marketing.group;

import ai.neargo.shop.marketing.group.dto.GroupVOs.GroupBuyVO;
import ai.neargo.shop.marketing.group.dto.GroupVOs.JoinResultVO;
import ai.neargo.shop.marketing.group.dto.GroupVOs.GroupPickupOrderVO;
import ai.neargo.shop.marketing.group.dto.GroupVOs.QuoteRevisionVO;
import ai.neargo.shop.marketing.group.dto.GroupVOs.QuoteVO;
import ai.neargo.shop.marketing.group.dto.GroupVOs.RequestVO;

import java.util.List;

/** 团购与求团（[API 清单 §2.8 / §3.8]）。列表与详情游客可看 —— 分享出去的链接要能打开。 */
public interface GroupService {

    List<GroupBuyVO> groupBuyList();

    GroupBuyVO groupBuyDetail(String groupNo);

    JoinResultVO join(String groupNo);

    /**
     * C 端发起团（C-GB-05）。商品必须已开放拼团 —— 团购价由商家在商品上配，
     * 用户只是把它「开出来」，不能自己定价。
     */
    GroupBuyVO createGroupBuy(CreateGroupBuyCommand cmd);

    /** 我发起的团。发起人要在这里看待取订单、做签收与核销 */
    List<GroupBuyVO> myHostedGroups();

    /**
     * <b>商家</b>开的团（B-11.9）。
     *
     * <p>与 {@link #myHostedGroups()} 是两回事：那个按发起人（C 端用户）查，
     * 这个按主体查。库上用 {@code initiator_user_no} 是否为空区分两者 ——
     * 商家开的团没有个人发起人，成团后的取货由门店承接，不是某个邻居家。
     */
    List<GroupBuyVO> merchantGroups(String merchantNo);

    /**
     * 商家开团。团购价与起团人数都来自**商品上已配好的拼团设置** ——
     * 商家不能在开团这一步临时定价，否则同一件货会有两个价，而 C 端已经看到过旧的那个。
     */
    GroupBuyVO createMerchantGroup(String merchantNo, String goodsNo);

    /** 本团待取订单。<b>作用域限本团</b>，且只有发起人能看 */
    List<GroupPickupOrderVO> groupPickupOrders(String groupNo);

    /** 批次签收：发起人确认整车货已到（必须在逐单核销之前） */
    GroupBuyVO confirmGroupBatch(String groupNo);

    /** 发起人核销本团某一单。核销码必须属于本团 */
    GroupPickupOrderVO verifyGroupPickup(String groupNo, String verifyCode);

    /** 求团：发起人确认收货，需求单收口 */
    RequestVO confirmRequest(String requestNo);

    /**
     * @param neighborAddress 勾了「送到我家」时的完整地址；为空表示走常规自提点
     */
    record CreateGroupBuyCommand(String goodsNo, String pickupNo,
                                 String neighborAddress, String neighborTimeSlot) {
    }

    RequestVO createRequest(CreateRequestCommand cmd);

    List<RequestVO> requestList();

    RequestVO requestDetail(String requestNo);

    /** +1 / 取消。**是意向不是订单**。 */
    RequestVO toggleInterest(String requestNo);

    List<QuoteVO> quotes(String requestNo);

    /** 发起人选定报价 → **锁价**（ADR-003）。 */
    RequestVO choose(String requestNo, String quoteNo);

    /** 改价公示（C 端读）。 */
    List<QuoteRevisionVO> priceHistory(String requestNo);

    List<RequestVO> pool();

    QuoteVO quote(String merchantNo, String requestNo, QuoteCommand cmd);

    QuoteVO revise(String merchantNo, String quoteNo, QuoteCommand cmd);

    // ---------------------------------------------------------------- 平台侧（P-8.2）

    /**
     * 所有需求单（平台视角，不按用户过滤）。
     *
     * <p>{@link #requestList()} 按当前登录者过滤——只看自己的；
     * 这一条是运营端全量视图：哪个邻居在等什么货、报价到了哪一步。
     *
     * @param status 为空给全部；传 {@code COLLECTING} / {@code QUOTED} / {@code CLOSED}
     */
    List<RequestVO> opsDemands(String status);

    /**
     * 平台报价列表。**不按 merchantNo 过滤**——平台要看到所有商家的报价。
     *
     * @param status 为空给全部；传 {@code BREACH} 就是毁约档
     */
    List<ai.neargo.shop.marketing.group.dto.OpsGroupVOs.OpsQuoteVO> opsQuotes(String status);

    /**
     * 平台改价（P-8.2.4）。留痕走与商家改价同一条路径，公示的是同一份价格历史。
     *
     * <p>为什么平台要能改价：报价写错一位数（12.00 打成 1.20）而商家联系不上时，
     * 用户已经按错价下了单。撤回整条报价会让已下单的人莫名其妙，改价+留痕才说得清。
     */
    QuoteVO opsRevisePrice(String quoteNo, long unitPriceMinor, String reason, String operatorNo);

    /**
     * 判定毁约（P-8.2.5）。报价置 {@code BREACH}，<b>同时写一条商家违规</b>，
     * 计入 {@code breach_count} —— 那个数字直接公示在报价卡上（ADR-003）。
     *
     * <p>不可撤销：毁约判定影响商家准入，要撤只能走申诉流程另开一条记录，
     * 而不是把这条抹掉。抹得掉的处置等于没有处置。
     */
    QuoteVO markBreach(String quoteNo, String detail, String operatorNo);

    /**
     * 平台拼团列表。**不按商家过滤**——平台要看到所有团。
     *
     * @param status 为空给全部
     */
    List<ai.neargo.shop.marketing.group.dto.OpsGroupVOs.OpsGroupVO> opsGroups(String status);

    /**
     * 平台中止拼团（P-8.1.2）。团置 {@code FAILED}，参团的人按流团处理。
     *
     * <p>此前平台对拼团**没有任何干预手段**：商家开了个违规团、或者标错了原价
     * 把「团购价」做成比原价还高，运营只能去改数据库。
     *
     * <p>只给「中止」不给「强制成团」：中止是止损，成团是替商家做生意决定——
     * 后者一旦出错（商家备不出货），承担后果的是不知情的用户。
     *
     * @param reason 中止理由，**必填且会展示给参团用户** —— 团没了总得给个说法
     */
    GroupBuyVO abortGroup(String groupNo, String reason, String operatorNo);

    record CreateRequestCommand(String title, String description, List<String> images,
                                int expectCount, int days) {
    }

    record QuoteCommand(long unitPriceMinor, int minQty, String note, int validDays) {
    }

    /**
     * 这家店<b>还能报价</b>的求团需求数（工作台待办）。
     *
     * <p>「还能」有两层：需求本身仍在收集/已有报价（{@code COLLECTING}/{@code QUOTED}），
     * 且**这家店还没报过**。不排掉自己已报的，商家会看到一个永远降不下去的待办 ——
     * 他每报一单，那个数字纹丝不动。
     */
    int quotableCount(String merchantNo);
}
