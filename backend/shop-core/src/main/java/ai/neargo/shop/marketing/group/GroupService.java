package ai.neargo.shop.marketing.group;

import ai.neargo.shop.marketing.group.dto.GroupVOs.GroupBuyVO;
import ai.neargo.shop.marketing.group.dto.GroupVOs.GroupPickupOrderVO;
import ai.neargo.shop.marketing.group.dto.GroupVOs.QuoteRevisionVO;
import ai.neargo.shop.marketing.group.dto.GroupVOs.QuoteVO;
import ai.neargo.shop.marketing.group.dto.GroupVOs.RequestVO;

import java.util.List;

/** 团购与求团（[API 清单 §2.8 / §3.8]）。列表与详情游客可看 —— 分享出去的链接要能打开。 */
public interface GroupService {

    List<GroupBuyVO> groupBuyList();

    GroupBuyVO groupBuyDetail(String groupNo);

    GroupBuyVO join(String groupNo);

    /**
     * C 端发起团（C-GB-05）。商品必须已开放拼团 —— 团购价由商家在商品上配，
     * 用户只是把它「开出来」，不能自己定价。
     */
    GroupBuyVO createGroupBuy(CreateGroupBuyCommand cmd);

    /** 我发起的团。发起人要在这里看待取订单、做签收与核销 */
    List<GroupBuyVO> myHostedGroups();

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
