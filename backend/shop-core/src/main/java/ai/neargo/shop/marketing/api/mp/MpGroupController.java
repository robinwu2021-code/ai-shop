package ai.neargo.shop.marketing.api.mp;

import ai.neargo.shop.marketing.group.GroupService;
import ai.neargo.shop.marketing.group.dto.GroupVOs.GroupPickupOrderVO;
import ai.neargo.shop.marketing.group.dto.GroupVOs.GroupBuyVO;
import ai.neargo.shop.marketing.group.dto.GroupVOs.QuoteRevisionVO;
import ai.neargo.shop.marketing.group.dto.GroupVOs.QuoteVO;
import ai.neargo.shop.marketing.group.dto.GroupVOs.RequestVO;
import jakarta.validation.constraints.NotBlank;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** C 端团购与求团（[API 清单 §2.8]）。 */
@RestController
@Validated
public class MpGroupController {

    private final GroupService groupService;

    public MpGroupController(GroupService groupService) {
        this.groupService = groupService;
    }

    @GetMapping("/mp/group-buy")
    public List<GroupBuyVO> groupBuyList() {
        return groupService.groupBuyList();
    }

    @GetMapping("/mp/group-buy/{groupNo}")
    public GroupBuyVO groupBuyDetail(@PathVariable String groupNo) {
        return groupService.groupBuyDetail(groupNo);
    }

    @PostMapping("/mp/group-buy/{groupNo}/join")
    public GroupBuyVO join(@PathVariable String groupNo) {
        return groupService.join(groupNo);
    }

    /** C 端发起团（C-GB-05）。团购价由商家在商品上配，开团人只是把它开出来 */
    @PostMapping("/mp/group-buy")
    public GroupBuyVO createGroupBuy(@RequestBody CreateGroupBuyReq req) {
        return groupService.createGroupBuy(new GroupService.CreateGroupBuyCommand(
                req.goodsNo(), req.pickupNo(),
                req.neighbor() == null ? null : req.neighbor().address(),
                req.neighbor() == null ? null : req.neighbor().timeSlot()));
    }

    @GetMapping("/mp/group-buy/hosted")
    public List<GroupBuyVO> myHostedGroups() {
        return groupService.myHostedGroups();
    }

    /** 本团待取订单。作用域限本团，且只有发起人能看（E16） */
    @GetMapping("/mp/group-buy/{groupNo}/orders")
    public List<GroupPickupOrderVO> groupPickupOrders(@PathVariable String groupNo) {
        return groupService.groupPickupOrders(groupNo);
    }

    /** 批次签收：整车货到了。必须在逐单核销之前 */
    @PostMapping("/mp/group-buy/{groupNo}/receive")
    public GroupBuyVO confirmGroupBatch(@PathVariable String groupNo) {
        return groupService.confirmGroupBatch(groupNo);
    }

    /** 发起人核销本团某一单 */
    @PostMapping("/mp/group-buy/{groupNo}/verify")
    public GroupPickupOrderVO verifyGroupPickup(@PathVariable String groupNo,
                                                @RequestBody VerifyReq req) {
        return groupService.verifyGroupPickup(groupNo, req.code());
    }

    /**
     * 求团发起人确认收货，需求单收口。
     *
     * @param neighbor 勾「送到我家」时的地址与时段。**只能是发起人自己家**（ADR-005）——
     *                 能指定别人家就是团长招募换了个名字
     */
    public record CreateGroupBuyReq(String goodsNo, String pickupNo, NeighborReq neighbor) {
    }

    public record NeighborReq(String address, String timeSlot) {
    }

    public record VerifyReq(String code) {
    }

    @GetMapping("/mp/group-request")
    public List<RequestVO> requestList() {
        return groupService.requestList();
    }

    @PostMapping("/mp/group-request")
    public RequestVO createRequest(@RequestBody CreateRequestReq req) {
        return groupService.createRequest(new GroupService.CreateRequestCommand(
                req.title(), req.description(), req.images(),
                req.expectCount() == null ? 1 : req.expectCount(),
                req.days() == null ? 7 : req.days()));
    }

    @GetMapping("/mp/group-request/{requestNo}")
    public RequestVO requestDetail(@PathVariable String requestNo) {
        return groupService.requestDetail(requestNo);
    }

    @PostMapping("/mp/group-request/{requestNo}/interest")
    public RequestVO toggleInterest(@PathVariable String requestNo) {
        return groupService.toggleInterest(requestNo);
    }

    @GetMapping("/mp/group-request/{requestNo}/quotes")
    public List<QuoteVO> quotes(@PathVariable String requestNo) {
        return groupService.quotes(requestNo);
    }

    @PostMapping("/mp/group-request/{requestNo}/choose")
    public RequestVO choose(@PathVariable String requestNo, @RequestBody ChooseReq req) {
        return groupService.choose(requestNo, req.quoteNo());
    }

    /** 改价公示（ADR-003）。**游客也能看** —— 公示的意义就在于人人可查。 */
    /** 求团发起人确认收货，需求单收口 */
    @PostMapping("/mp/group-request/{requestNo}/confirm")
    public RequestVO confirmRequest(@PathVariable String requestNo) {
        return groupService.confirmRequest(requestNo);
    }

    @GetMapping("/mp/group-request/{requestNo}/price-history")
    public List<QuoteRevisionVO> priceHistory(@PathVariable String requestNo) {
        return groupService.priceHistory(requestNo);
    }

    public record CreateRequestReq(@NotBlank String title, String description, List<String> images,
                                   Integer expectCount, Integer days) {
    }

    public record ChooseReq(@NotBlank String quoteNo) {
    }
}
