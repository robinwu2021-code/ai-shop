package ai.neargo.shop.portal.biz;

import ai.neargo.shop.auth.BizContext;
import ai.neargo.shop.auth.BizPerms;
import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.merchant.service.AppointmentSlotService;
import ai.neargo.shop.merchant.service.StoreFulfillmentService;
import ai.neargo.shop.spi.user.MerchantQueryPort;
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
 *
 * <p><b>门店级判权</b>（{@link BizContext#requireStoreAccess}）：路径里的门店号必须是这个人被授权的店。
 * 只判主体的话，只管 A 店的店员把路径换成 B 店，就能改 B 店的送货方式、开停 B 店的时段；
 * 而时段列表此前连主体都不判。
 */
@Profile("api")
@RestController
public class BizStoreFulfillmentController {

    private final StoreFulfillmentService fulfillmentService;
    private final AppointmentSlotService appointmentSlotService;
    private final MerchantQueryPort merchantPort;

    public BizStoreFulfillmentController(StoreFulfillmentService fulfillmentService,
                                         AppointmentSlotService appointmentSlotService,
                                         MerchantQueryPort merchantPort) {
        this.fulfillmentService = fulfillmentService;
        this.appointmentSlotService = appointmentSlotService;
        this.merchantPort = merchantPort;
    }

    /**
     * 路径门店号 → 本次作用的门店，并判门店级权限。"default" 解析成主体默认店再判 ——
     * 店员不一定被授权到默认店。返回 {@code null} 交给服务按「默认店」处理，与原约定一致。
     */
    private String accessibleStore(String merchantNo, String storeNo) {
        if ("default".equals(storeNo)) {
            BizContext.requireStoreAccess(merchantPort.defaultStoreNo(merchantNo)
                    .orElseThrow(() -> BizException.of(ErrorCode.NOT_FOUND)));
            return null;
        }
        return BizContext.requireStoreAccess(storeNo);
    }

    // ---------------------------------------------------------------- 门店送货方式（方案 v4）

    /**
     * 门店履约全景：四路各一行（开关/置灰原因/快递模板）。
     * {@code storeNo} 空 = 默认门店 —— 单店商家的端上不用感知门店号。
     */
    @PreAuthorize("@perm.canBiz('" + BizPerms.STORE + "')")
    @GetMapping("/biz/stores/{storeNo}/fulfillment")
    public StoreFulfillmentService.FulfillmentVO storeFulfillment(@PathVariable String storeNo) {
        String merchantNo = BizContext.requireMerchantNo();
        return fulfillmentService.get(merchantNo, accessibleStore(merchantNo, storeNo));
    }

    /**
     * 全量保存门店送货方式。「关一路」是 enabled=false 不是删行 —— 配置原地保留。
     */
    @PreAuthorize("@perm.canBiz('" + BizPerms.STORE + "')")
    @PutMapping("/biz/stores/{storeNo}/fulfillment")
    public StoreFulfillmentService.FulfillmentVO saveStoreFulfillment(
            @PathVariable String storeNo, @RequestBody FulfillmentReq req) {
        String merchantNo = BizContext.requireMerchantNo();
        return fulfillmentService.save(merchantNo, accessibleStore(merchantNo, storeNo), req.channels());
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
        BizContext.requireMerchantNo();
        return appointmentSlotService.list(BizContext.requireStoreAccess(storeNo), from, to, false);
    }

    /** 开一个时段。 */
    @PreAuthorize("@perm.canBiz('" + BizPerms.STORE + "')")
    @PostMapping("/biz/stores/{storeNo}/appointment-slots")
    public AppointmentSlotService.SlotVO openSlot(@PathVariable String storeNo,
                                                 @RequestBody SlotReq req) {
        return appointmentSlotService.open(BizContext.requireMerchantNo(), BizContext.requireStoreAccess(storeNo),
                req.startAt(), req.endAt(), req.capacity());
    }

    /**
     * 停约。<b>不删行，也不动已经约进来的单</b> —— 语义是「别再往里放人」。
     * 赶人得先有一套通知与补偿的规则，在那之前悄悄取消别人的预约比不支持停约糟得多。
     */
    @PreAuthorize("@perm.canBiz('" + BizPerms.STORE + "')")
    @PostMapping("/biz/appointment-slots/{slotNo}/close")
    public AppointmentSlotService.SlotVO closeSlot(@PathVariable String slotNo) {
        String merchantNo = BizContext.requireMerchantNo();
        BizContext.requireStoreAccess(appointmentSlotService.storeOf(merchantNo, slotNo));
        return appointmentSlotService.close(merchantNo, slotNo);
    }

    public record SlotReq(long startAt, long endAt, int capacity) {
    }
}
