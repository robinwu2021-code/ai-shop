package ai.neargo.shop.trade.api.biz;

import ai.neargo.shop.auth.BizPerms;
import org.springframework.security.access.prepost.PreAuthorize;
import ai.neargo.shop.auth.BizContext;
import ai.neargo.shop.common.PageData;
import ai.neargo.shop.trade.dto.CourierOrderVO;
import ai.neargo.shop.trade.dto.OrderVO;
import ai.neargo.shop.trade.service.MerchantOrderService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.RestController;

/**
 * B 端商家订单（[API 清单 §3.4]）。作用域是 {@code entity_no}。
 *
 * <p>返回的是**子单**（一个子单 = 一个商家的一次交易）—— 与 C 端 Q6 的粒度一致，
 * 双方谈同一个订单号时不会各说各的。
 */
@Profile("api")
@RestController
public class BizOrderController {

    private final MerchantOrderService merchantOrderService;

    public BizOrderController(MerchantOrderService merchantOrderService) {
        this.merchantOrderService = merchantOrderService;
    }

    /**
     * 订单列表。
     *
     * <p><b>返回两种档次</b>：配送员拿 {@link CourierOrderVO}（无金额、无核销码），
     * 其余角色拿完整 {@link OrderVO}。判断见 {@link BizContext#courierOnlyOrderView()} ——
     * 一人多岗时以更宽的那一档为准，店员兼配送仍是完整视图。
     */
    @PreAuthorize("@perm.canBiz('" + BizPerms.ORDER_VIEW + "')")
    @GetMapping("/biz/order")
    public PageData<?> orders(@RequestParam(required = false) String status,
                              @RequestParam(required = false) Boolean allStores,
                              @RequestParam(defaultValue = "1") long page,
                              @RequestParam(defaultValue = "10") long size) {
        /*
         * 默认只给**当前门店**的单。要看全部时端上传 allStores=true ——
         * 默认给全部的话，多门店老板打开订单页看到的是几家店混在一起的流水，
         * 而他要做的事（今天这家店要发哪些货）恰恰是分店的。
         *
         * **「全部」对老板和店员不是一回事**：老板的全部是主体名下所有店，
         * 店员的全部只是他被授权的那几家。不区分的话，店员点一下「全部门店」
         * 就能看到别家店的单 —— 而这不会报错，只会安静地多看到一些东西。
         */
        var ctx = BizContext.current();
        java.util.Collection<String> storeNos = Boolean.TRUE.equals(allStores)
                ? ctx.allowedStoresOrAll()
                : java.util.List.of(ctx.currentStoreNo() == null ? "" : ctx.currentStoreNo());
        PageData<OrderVO> full = merchantOrderService.list(ctx.requireMerchantNo(), storeNos,
                status, page, Math.min(size, 50));
        return ctx.courierOnlyOrderView() ? narrow(full) : full;
    }

    /** 整页裁到配送员那一档。分页元信息原样带过去 —— 裁的是每一行，不是这一页有几行。 */
    private static PageData<CourierOrderVO> narrow(PageData<OrderVO> p) {
        return PageData.of(p.records().stream().map(CourierOrderVO::of).toList(),
                p.total(), p.page(), p.size());
    }

    /**
     * 订单详情。
     *
     * <p>作用域是**当前门店**：多门店之后店员只被授权到某几家，
     * 只按主体判的话 A 店店员能翻出 B 店的单。
     *
     * <p>与列表**同一档次规则**：配送员在这里也只拿裁剪档。
     * 两个端点分开判的话，列表裁了详情没裁，点进去照样看得到金额 ——
     * 而那种漏洞看起来完全正常。
     */
    @PreAuthorize("@perm.canBiz('" + BizPerms.ORDER_VIEW + "')")
    @GetMapping("/biz/order/{subOrderNo}")
    public Object detail(@PathVariable String subOrderNo) {
        var ctx = BizContext.current();
        OrderVO full = merchantOrderService.detail(ctx.requireMerchantNo(),
                ctx.currentStoreNo(), subOrderNo);
        return ctx.courierOnlyOrderView() ? CourierOrderVO.of(full) : full;
    }

    /** 发货：快递单号必填 —— 没有单号的「已发货」对买家没有任何用处。 */
    @PreAuthorize("@perm.canBiz('" + BizPerms.SHIP + "')")
    @PostMapping("/biz/order/{subOrderNo}/ship")
    public OrderVO ship(@PathVariable String subOrderNo, @RequestBody ShipReq req) {
        var ctx = BizContext.current();
        return merchantOrderService.ship(ctx.requireMerchantNo(), ctx.currentStoreNo(),
                subOrderNo, req.expressNo());
    }

    /**
     * 标记送达。**不是「确认收货」** —— 那是买家的动作，
     * 两者都推到 COMPLETED 但责任方不同，所以各走各的入口、各自留痕。
     */
    @PreAuthorize("@perm.canBiz('" + BizPerms.SHIP + "')")
    @PostMapping("/biz/order/{subOrderNo}/delivered")
    public OrderVO delivered(@PathVariable String subOrderNo) {
        var ctx = BizContext.current();
        return merchantOrderService.delivered(ctx.requireMerchantNo(), ctx.currentStoreNo(), subOrderNo);
    }

    /** @param expressNo 快递单号 */
    public record ShipReq(String expressNo) {
    }
}
