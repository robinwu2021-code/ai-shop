package ai.neargo.shop.pay.api.ops;

import ai.neargo.shop.auth.Perms;
import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.pay.acquiring.service.PayOpsQueryService;
import ai.neargo.shop.pay.acquiring.service.PayOpsQueryService.OrderDetail;
import ai.neargo.shop.pay.acquiring.service.PayOpsQueryService.OrderFilter;
import ai.neargo.shop.pay.acquiring.service.PayOpsQueryService.OrderRow;
import java.util.List;
import org.springframework.context.annotation.Profile;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 支付单列表与全貌（F50）：交易尝试、退款、流水凭证、答复、异常放在一页上 —— 查一笔钱不用翻五张表 */
@Profile("ops")
@RestController
@RequestMapping("/pay-ops/v1/orders")
public class PayOpsOrderController {

    private final PayOpsQueryService query;

    public PayOpsOrderController(PayOpsQueryService query) {
        this.query = query;
    }

    @PreAuthorize("@perm.can('" + Perms.PAY_ORDER_VIEW + "')")
    @GetMapping
    public List<OrderRow> list(@RequestParam(required = false) String status,
                               @RequestParam(required = false) String payOrderNo,
                               @RequestParam(required = false) String outOrderNo,
                               @RequestParam(required = false) Long storeId,
                               @RequestParam(required = false) String bizType,
                               @RequestParam(defaultValue = "1") int page,
                               @RequestParam(defaultValue = "20") int size) {
        return query.orders(new OrderFilter(status, payOrderNo, outOrderNo, storeId, bizType), page, size);
    }

    @PreAuthorize("@perm.can('" + Perms.PAY_ORDER_VIEW + "')")
    @GetMapping("/{payOrderNo}")
    public OrderDetail detail(@PathVariable String payOrderNo) {
        OrderDetail d = query.detail(payOrderNo);
        if (d == null) {
            throw BizException.of(ErrorCode.PAY_ORDER_NOT_FOUND);
        }
        return d;
    }
}
