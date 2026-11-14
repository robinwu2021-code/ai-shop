package ai.neargo.shop.pay.api.biz;

import ai.neargo.shop.auth.BizPerms;
import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.pay.acquiring.service.PayStatementService;
import ai.neargo.shop.pay.acquiring.service.PayStatementService.DailyStatement;
import ai.neargo.shop.pay.funds.BizDates;
import ai.neargo.shop.pay.merchant.service.PayMerchantBindingService;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import org.springframework.context.annotation.Profile;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 本店收款日流水（F43）。{@code day} 不传 = 门店市场的「今天」 */
@Profile("api")
@RestController
@RequestMapping("/biz/pay/statement")
public class BizPayStatementController {

    private final PayStatementService statements;
    private final PayMerchantBindingService stores;
    private final BizPayContext ctx;

    public BizPayStatementController(PayStatementService statements, PayMerchantBindingService stores, BizPayContext ctx) {
        this.statements = statements;
        this.stores = stores;
        this.ctx = ctx;
    }

    @PreAuthorize("@perm.canBiz('" + BizPerms.PAY_STATEMENT + "')")
    @GetMapping
    public DailyStatement daily(@RequestParam(required = false) String day) {
        Long storeId = ctx.storeId();
        LocalDate d;
        try {
            d = day == null || day.isBlank()
                    ? BizDates.of(stores.storeById(storeId).map(PayMerchantBindingService.StoreRef::market).orElse("CN"),
                            Instant.now())
                    : LocalDate.parse(day);
        } catch (DateTimeParseException e) {
            throw BizException.of(ErrorCode.BAD_REQUEST);
        }
        return statements.daily(ctx.caller(storeId), storeId, d);
    }
}
