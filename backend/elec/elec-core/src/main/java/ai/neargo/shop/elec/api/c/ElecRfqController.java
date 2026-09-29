package ai.neargo.shop.elec.api.c;

import ai.neargo.shop.auth.SecurityUtils;
import ai.neargo.shop.elec.config.ConditionalOnElec;
import ai.neargo.shop.elec.dto.RfqDtos.RfqReq;
import ai.neargo.shop.elec.dto.RfqDtos.RfqView;
import ai.neargo.shop.elec.service.ElecRfqService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 元器件 C 端 · 询价。要登录，且要有手机号（服务层兜底，端上先弹手机号闸）。
 * 第一步由平台收单：提交后企业微信群收到，平台录入报价后微信通知买家，买家在这里接受。
 */
@ConditionalOnElec
@RestController
public class ElecRfqController {

    private final ElecRfqService rfqs;

    public ElecRfqController(ElecRfqService rfqs) {
        this.rfqs = rfqs;
    }

    @PostMapping("/elec/c/rfq")
    public RfqView submit(@RequestBody RfqReq req) {
        return rfqs.submit(SecurityUtils.currentUserNo(), req);
    }

    @GetMapping("/elec/c/rfq")
    public List<RfqView> mine(@RequestParam(defaultValue = "1") int page,
                              @RequestParam(defaultValue = "20") int size) {
        return rfqs.mine(SecurityUtils.currentUserNo(), page, size);
    }

    @GetMapping("/elec/c/rfq/{rfqNo}")
    public RfqView detail(@PathVariable String rfqNo) {
        return rfqs.detail(SecurityUtils.currentUserNo(), rfqNo);
    }

    /** 接受平台的报价。只在已报价且没过期时可以；企业微信群收到「买家接受了」 */
    @PostMapping("/elec/c/rfq/{rfqNo}/accept")
    public RfqView accept(@PathVariable String rfqNo) {
        return rfqs.accept(SecurityUtils.currentUserNo(), rfqNo);
    }
}
