package ai.neargo.shop.trade.api.ops;

import ai.neargo.shop.common.PageData;
import ai.neargo.shop.auth.Perms;
import ai.neargo.shop.trade.dto.OrderVO;
import ai.neargo.shop.trade.service.PlatformOrderService;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 平台端 · 订单查询（由原 {@code OpsController} 拆出，S7）。 */
@RestController
@Validated
public class OpsOrderController {

    private final PlatformOrderService platformOrderService;

    public OpsOrderController(PlatformOrderService platformOrderService) {
        this.platformOrderService = platformOrderService;
    }

    @GetMapping("/ops/order")
    @PreAuthorize("@perm.can('" + Perms.ORDER_VIEW + "')")
    public PageData<OrderVO> orders(@RequestParam(required = false) String status,
                                    @RequestParam(defaultValue = "1") long page,
                                    @RequestParam(defaultValue = "20") long size) {
        // size 上限 100：运营页面没有一次拉全量的正当理由，而没有上限时
        // 一次 size=100000 就能把连接池占满
        return platformOrderService.search(status, page, Math.min(size, 100));
    }
}
