package ai.neargo.shop.portal.biz;

import ai.neargo.shop.auth.BizContext;
import ai.neargo.shop.auth.BizPerms;
import ai.neargo.shop.merchant.service.AppointmentSlotService;
import ai.neargo.shop.merchant.service.StoreFulfillmentService;
import org.springframework.context.annotation.Profile;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 商家端 · <b>这家店怎么履约</b>：送货方式与预约排期。
 *
 * <p>从 {@code BizMerchantController} 抽出来的第一块（架构评审 §5.2）。
 * 那个类当时 <b>40 个端点 / 1021 行 / 九个不相干的节</b>
 * （店铺资料、资质、送货方式、预约排期、社区提报、收款进件、门店管理、员工授权、角色）。
 *
 * <p>⚠️ <b>预约排期那一节是我加进去的</b>（2026-08-26）。加的时候理由是
 * 「它和送货方式同类，权限也一样（{@code biz:store}）」—— 那个理由本身没错，
 * 错在<b>没有回头看那个类已经多大了</b>。一个 1021 行的类，
 * 每个往里加的人都有一个局部合理的理由。
 *
 * <p><b>这两节放在一起是有道理的</b>，而不只是「都跟门店有关」：
 * 它们回答的是同一个问题 ——「这家店能怎么把东西交到买家手上」。
 * 送货方式说的是<b>路径</b>（自提 / 自送 / 快递），排期说的是<b>容量</b>
 * （上门服务同时能接几单）。下单时两者是串在一起判的。
 *
 * <p>纯搬家：方法体、注解、路径、权限码<b>逐字未动</b>。
 */
@Profile("api")
@RestController
public class BizStoreFulfillmentController {

    private final StoreFulfillmentService fulfillmentService;
    private final AppointmentSlotService appointmentSlotService;

    public BizStoreFulfillmentController(StoreFulfillmentService fulfillmentService,
                                         AppointmentSlotService appointmentSlotService) {
        this.fulfillmentService = fulfillmentService;
        this.appointmentSlotService = appointmentSlotService;
    }

    // ---------------------------------------------------------------- 门店送货方式（方案 v4）

    /**
     * 门店履约全景：四路各一行（开关/置灰原因/快递模板）。
     * {@code storeNo} 空 = 默认门店 —— 单店商家的端上不用感知门店号。
     */
    @PreAuthorize("@perm.canBiz('" + BizPerms.STORE + "')")
    @GetMapping("/biz/stores/{storeNo}/fulfillment")
    public StoreFulfillmentService.FulfillmentVO storeFulfillment(@PathVariable String storeNo) {
        return fulfillmentService.get(BizContext.requireMerchantNo(),
                "default".equals(storeNo) ? null : storeNo);
    }

    /**
     * 全量保存门店送货方式。「关一路」是 enabled=false 不是删行 —— 配置原地保留。
     */
    @PreAuthorize("@perm.canBiz('" + BizPerms.STORE + "')")
    @PutMapping("/biz/stores/{storeNo}/fulfillment")
    public StoreFulfillmentService.FulfillmentVO saveStoreFulfillment(
            @PathVariable String storeNo, @RequestBody FulfillmentReq req) {
        return fulfillmentService.save(BizContext.requireMerchantNo(),
                "default".equals(storeNo) ? null : storeNo, req.channels());
    }

    /** 对齐 shared {@code StoreFulfillment}。 */
    public record FulfillmentReq(List<StoreFulfillmentService.ChannelCmd> channels) {
    }

    // ---------------------------------------------------------------- 预约排期

    /**
     * 本店的时段列表。<b>连约满的和停掉的一起列</b> ——
     * 只给「还能约的」的话，商家看不出「为什么这周没人约」到底是
     * 「没开时段」还是「开的都满了」，而这两件事该做的动作完全相反。
     */
    @PreAuthorize("@perm.canBiz('" + BizPerms.STORE + "')")
    @GetMapping("/biz/stores/{storeNo}/appointment-slots")
    public List<AppointmentSlotService.SlotVO> slots(
            @PathVariable String storeNo,
            @RequestParam long from, @RequestParam long to) {
        return appointmentSlotService.list(storeNo, from, to, false);
    }

    /** 开一个时段。 */
    @PreAuthorize("@perm.canBiz('" + BizPerms.STORE + "')")
    @PostMapping("/biz/stores/{storeNo}/appointment-slots")
    public AppointmentSlotService.SlotVO openSlot(@PathVariable String storeNo,
                                                 @RequestBody SlotReq req) {
        return appointmentSlotService.open(BizContext.requireMerchantNo(), storeNo,
                req.startAt(), req.endAt(), req.capacity());
    }

    /**
     * 停约。<b>不删行，也不动已经约进来的单</b> —— 语义是「别再往里放人」。
     * 赶人得先有一套通知与补偿的规则，在那之前悄悄取消别人的预约比不支持停约糟得多。
     */
    @PreAuthorize("@perm.canBiz('" + BizPerms.STORE + "')")
    @PostMapping("/biz/appointment-slots/{slotNo}/close")
    public AppointmentSlotService.SlotVO closeSlot(@PathVariable String slotNo) {
        return appointmentSlotService.close(BizContext.requireMerchantNo(), slotNo);
    }

    public record SlotReq(long startAt, long endAt, int capacity) {
    }
}
