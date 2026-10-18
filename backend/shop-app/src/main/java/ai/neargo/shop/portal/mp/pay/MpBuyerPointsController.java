package ai.neargo.shop.portal.mp.pay;

import ai.neargo.shop.auth.SecurityUtils;
import ai.neargo.shop.payclient.BuyerPointsAppService;

import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** Buyer-owned, type-separated views of the pay points ledger. */
@Profile("api")
@RestController
@RequestMapping("/mp/points")
public class MpBuyerPointsController {
    private final BuyerPointsAppService buyer;

    public MpBuyerPointsController(BuyerPointsAppService buyer) {
        this.buyer = buyer;
    }

    @GetMapping("/accounts")
    public List<BuyerPointsAppService.Account> accounts() {
        return buyer.accounts(SecurityUtils.requireUser().userNo());
    }

    @GetMapping("/entries")
    public BuyerPointsAppService.EntryPage entries(
            @RequestParam String typeCode,
            @RequestParam(defaultValue = "0") long after,
            @RequestParam(defaultValue = "20") int limit) {
        if (typeCode.isBlank() || after < 0 || limit < 1 || limit > 50) {
            throw new IllegalArgumentException("POINTS_ENTRIES_QUERY_INVALID");
        }
        return buyer.records(SecurityUtils.requireUser().userNo(), typeCode, after, limit);
    }
}
