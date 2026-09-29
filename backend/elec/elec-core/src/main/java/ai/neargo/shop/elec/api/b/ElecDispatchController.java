package ai.neargo.shop.elec.api.b;

import ai.neargo.shop.auth.SecurityUtils;
import ai.neargo.shop.elec.config.ConditionalOnElec;
import ai.neargo.shop.elec.dto.RfqDtos.DeclineReq;
import ai.neargo.shop.elec.dto.RfqDtos.DispatchView;
import ai.neargo.shop.elec.dto.RfqDtos.SupplierQuoteReq;
import ai.neargo.shop.elec.service.ElecDispatchService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 元器件 B 端 · 求购与报价。
 *
 * <p>供应商看得到<b>派给他的求购</b>（料号、数量、要求），看不到买家是谁：
 * 这一侧用的单号是 dispatch_no，与买家手里的 rfq_no 是两个号，对不上。
 */
@ConditionalOnElec
@RestController
public class ElecDispatchController {

    private final ElecDispatchService dispatches;

    public ElecDispatchController(ElecDispatchService dispatches) {
        this.dispatches = dispatches;
    }

    /** @param status SENT 待报价 / VIEWED / QUOTED 已报价 / DECLINED；不传 = 全部 */
    @GetMapping("/elec/b/rfq")
    public List<DispatchView> mine(@RequestParam(required = false) String status,
                                   @RequestParam(defaultValue = "1") int page,
                                   @RequestParam(defaultValue = "20") int size) {
        return dispatches.mine(SecurityUtils.currentUserNo(), status, page, size);
    }

    /** 看详情会把状态从「待报价」标成「看过」—— 响应率的分母是看到的，不是派出去的 */
    @GetMapping("/elec/b/rfq/{dispatchNo}")
    public DispatchView detail(@PathVariable String dispatchNo) {
        return dispatches.detail(SecurityUtils.currentUserNo(), dispatchNo);
    }

    /** 报价。同一条派单再报一次就是改价（覆盖，不是新增一条） */
    @PostMapping("/elec/b/rfq/{dispatchNo}/quote")
    public DispatchView quote(@PathVariable String dispatchNo, @RequestBody SupplierQuoteReq req) {
        return dispatches.quote(SecurityUtils.currentUserNo(), dispatchNo, req);
    }

    /** 没货就直说。**拒绝也算响应** —— 不回才伤响应率 */
    @PostMapping("/elec/b/rfq/{dispatchNo}/decline")
    public DispatchView decline(@PathVariable String dispatchNo, @RequestBody(required = false) DeclineReq req) {
        return dispatches.decline(SecurityUtils.currentUserNo(), dispatchNo, req);
    }
}
