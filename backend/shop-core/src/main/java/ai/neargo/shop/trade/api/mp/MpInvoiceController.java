package ai.neargo.shop.trade.api.mp;

import ai.neargo.shop.trade.service.InvoiceRequestService;
import org.springframework.context.annotation.Profile;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 消费者端 · 开票申请（ADR-017 §3.4 条件 2）。
 *
 * <p><b>这三个端点此前一个都没有。</b>C 端只有下单前一句
 * 「本商家无法开具发票」——连申请的地方都没有。
 * 而归集路径要成立，「平台开票给消费者」是四个必要条件之一：
 * <b>没有入口 = 没有履行途径</b>。
 */
@Profile("api")
@RestController
@Validated
public class MpInvoiceController {

    private final InvoiceRequestService service;

    public MpInvoiceController(InvoiceRequestService service) {
        this.service = service;
    }

    @PostMapping("/mp/invoice/apply")
    public InvoiceRequestService.InvoiceRequestVO apply(
            @RequestBody InvoiceRequestService.ApplyCommand cmd) {
        return service.apply(cmd);
    }

    @GetMapping("/mp/invoice/mine")
    public List<InvoiceRequestService.InvoiceRequestVO> mine() {
        return service.mine();
    }

    /**
     * 某单的申请状态。**返回 null 而不是 404** ——
     * 「这单还没申请过」是常态不是错误，端上据此显示「申请发票」按钮。
     */
    @GetMapping("/mp/invoice/order/{orderNo}")
    public InvoiceRequestService.InvoiceRequestVO ofOrder(@PathVariable String orderNo) {
        return service.ofOrder(orderNo);
    }
}
