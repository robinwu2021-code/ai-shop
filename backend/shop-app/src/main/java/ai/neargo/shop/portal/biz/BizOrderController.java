package ai.neargo.shop.portal.biz;

import ai.neargo.shop.auth.BizContext;
import ai.neargo.shop.common.PageData;
import ai.neargo.shop.trade.dto.OrderVO;
import ai.neargo.shop.trade.service.MerchantOrderService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * B 端商家订单（[API 清单 §3.4]）。作用域是 {@code merchant_no}。
 *
 * <p>返回的是**子单**（一个子单 = 一个商家的一次交易）—— 与 C 端 Q6 的粒度一致，
 * 双方谈同一个订单号时不会各说各的。
 */
@RestController
public class BizOrderController {

    private final MerchantOrderService merchantOrderService;

    public BizOrderController(MerchantOrderService merchantOrderService) {
        this.merchantOrderService = merchantOrderService;
    }

    @GetMapping("/biz/order")
    public PageData<OrderVO> orders(@RequestParam(required = false) String status,
                                    @RequestParam(defaultValue = "1") long page,
                                    @RequestParam(defaultValue = "10") long size) {
        return merchantOrderService.list(BizContext.current().requireMerchantNo(), status,
                page, Math.min(size, 50));
    }
}
