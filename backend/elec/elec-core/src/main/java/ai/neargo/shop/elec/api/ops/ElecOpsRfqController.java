package ai.neargo.shop.elec.api.ops;

import ai.neargo.elec.api.ElecInternal;
import ai.neargo.shop.elec.config.ConditionalOnElec;
import ai.neargo.shop.elec.dto.RfqDtos.CloseReq;
import ai.neargo.shop.elec.dto.RfqDtos.OpsRfqView;
import ai.neargo.shop.elec.dto.RfqDtos.QuoteReq;
import ai.neargo.shop.elec.service.ElecRfqService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 运营端 · 元器件询价。第一步询价<b>以平台为准</b>：运营在这里看询价（连同库里谁有货）、录入报价、关单。
 * 录入报价与「暂无货源」关单都会微信通知买家。
 *
 * <p>令牌是运营端的（otk_），权限码 {@code elec:rfq:read} / {@code elec:rfq:quote}。
 */
@ConditionalOnElec
@RestController
public class ElecOpsRfqController {

    private final ElecRfqService rfqs;

    public ElecOpsRfqController(ElecRfqService rfqs) {
        this.rfqs = rfqs;
    }

    /** @param status SUBMITTED / QUOTED / ACCEPTED / CLOSED；不传 = 全部 */
    @GetMapping("/elec/ops/rfq")
    public List<OpsRfqView> list(@RequestParam(required = false) String status,
                                 @RequestParam(defaultValue = "1") int page,
                                 @RequestParam(defaultValue = "20") int size) {
        ElecOpsGuard.require(ElecInternal.PERM_RFQ_READ);
        return rfqs.opsList(status, page, size);
    }

    @GetMapping("/elec/ops/rfq/{rfqNo}")
    public OpsRfqView detail(@PathVariable String rfqNo) {
        ElecOpsGuard.require(ElecInternal.PERM_RFQ_READ);
        return rfqs.opsDetail(rfqNo);
    }

    /** 录入（或改）报价。没列出的行 = 没找到货。写完微信通知买家 */
    @PostMapping("/elec/ops/rfq/{rfqNo}/quote")
    public OpsRfqView quote(@PathVariable String rfqNo, @RequestBody QuoteReq req) {
        return rfqs.quote(ElecOpsGuard.require(ElecInternal.PERM_RFQ_QUOTE), rfqNo, req);
    }

    /** 关单：NO_SOURCE 暂无货源（通知买家）/ BUYER_CANCELLED / DONE */
    @PostMapping("/elec/ops/rfq/{rfqNo}/close")
    public OpsRfqView close(@PathVariable String rfqNo, @RequestBody CloseReq req) {
        return rfqs.close(ElecOpsGuard.require(ElecInternal.PERM_RFQ_QUOTE), rfqNo, req);
    }
}
