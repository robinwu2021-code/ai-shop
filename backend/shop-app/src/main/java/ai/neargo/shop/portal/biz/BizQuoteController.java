package ai.neargo.shop.portal.biz;

import ai.neargo.shop.auth.BizContext;
import ai.neargo.shop.marketing.group.GroupService;
import ai.neargo.shop.marketing.group.dto.GroupVOs.QuoteVO;
import ai.neargo.shop.marketing.group.dto.GroupVOs.RequestVO;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * B 端求团报价（[API 清单 §3.8]）。
 *
 * <p>**报价不做事前审核**（ADR-003）：这里没有任何审核环节，
 * 约束来自锁价、改价公示与毁约记录 —— 见 {@code GroupServiceImpl}。
 */
@RestController
public class BizQuoteController {

    private final GroupService groupService;

    public BizQuoteController(GroupService groupService) {
        this.groupService = groupService;
    }

    @GetMapping("/biz/group-request/pool")
    public List<RequestVO> pool() {
        BizContext.requireMerchantNo();
        return groupService.pool();
    }

    @PostMapping("/biz/group-request/{requestNo}/quote")
    public QuoteVO quote(@PathVariable String requestNo, @RequestBody QuoteReq req) {
        return groupService.quote(BizContext.requireMerchantNo(), requestNo, req.toCommand());
    }

    @PostMapping("/biz/quote/{quoteNo}/revise")
    public QuoteVO revise(@PathVariable String quoteNo, @RequestBody QuoteReq req) {
        return groupService.revise(BizContext.requireMerchantNo(), quoteNo, req.toCommand());
    }

    public record QuoteReq(Long unitPriceMinor, Integer minQty, String note, Integer validDays) {

        GroupService.QuoteCommand toCommand() {
            return new GroupService.QuoteCommand(
                    unitPriceMinor == null ? 0L : unitPriceMinor,
                    minQty == null ? 0 : minQty, note,
                    validDays == null ? 7 : validDays);
        }
    }
}
