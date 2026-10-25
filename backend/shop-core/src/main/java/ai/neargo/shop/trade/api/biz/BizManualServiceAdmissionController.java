package ai.neargo.shop.trade.api.biz;

import ai.neargo.shop.auth.BizContext;
import ai.neargo.shop.auth.BizPerms;
import ai.neargo.shop.auth.SecurityUtils;
import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.spi.trade.CheckoutPort.CheckoutQuote;
import ai.neargo.shop.spi.trade.CheckoutPort.CheckoutScope;
import ai.neargo.shop.spi.trade.CheckoutPort.OperationRef;
import ai.neargo.shop.spi.trade.CheckoutPort.PrepareCheckoutReq;
import ai.neargo.shop.spi.trade.CheckoutPort.SourceLookup;
import ai.neargo.shop.spi.trade.ManualServiceAdmissionPort.ConfirmManualReceipt;
import ai.neargo.shop.spi.trade.ManualServiceAdmissionPort.ManualAdmissionView;
import ai.neargo.shop.spi.trade.ManualServiceAdmissionPort.ManualLookup;
import ai.neargo.shop.spi.trade.ManualServiceAdmissionPort.ManualSourceFacts;
import ai.neargo.shop.trade.service.ManualServiceAdmissionService;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;

import org.springframework.context.annotation.Profile;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Scope comes only from current B context; path owner and header keys are checked before effects.
 */
@RestController
@Profile("api")
@Validated
public class BizManualServiceAdmissionController {
    private final ManualServiceAdmissionService manual;

    public BizManualServiceAdmissionController(ManualServiceAdmissionService manual) {
        this.manual = manual;
    }

    @PostMapping("/biz/service-orders/manual-quotes")
    @PreAuthorize("@perm.canBiz('" + BizPerms.CHECKOUT_PREPARE + "')")
    public CheckoutQuote prepare(
            @RequestBody @Valid PrepareCheckoutReq request,
            @RequestHeader("Idempotency-Key") @NotBlank String key) {
        sameKey(key, request.requestKey());
        return manual.prepare(scope(), request);
    }

    @PostMapping("/biz/service-orders/{orderNo}/manual-receipts")
    @PreAuthorize(
            "@perm.canBiz('"
                    + BizPerms.CASH_RECEIVE
                    + "') and @perm.canBiz('"
                    + BizPerms.MANUAL_CONFIRM
                    + "')")
    public ManualAdmissionView confirm(
            @PathVariable String orderNo,
            @RequestBody @Valid ConfirmManualReceipt request,
            @RequestHeader("Idempotency-Key") @NotBlank String key) {
        sameKey(key, request.requestKey());
        return manual.confirmForOrder(scope(), orderNo, request);
    }

    @GetMapping("/biz/service-orders/{orderNo}/manual-admission")
    @PreAuthorize("@perm.canBiz('" + BizPerms.ORDER_VIEW + "')")
    public ManualAdmissionView query(
            @PathVariable String orderNo,
            @RequestParam String operationNo,
            @RequestParam(required = false) String declarationNo,
            @RequestHeader(value = "Idempotency-Key", required = false) String key) {
        return manual.queryForOrder(
                scope(),
                orderNo,
                new ManualLookup(new OperationRef("BEAUTY", operationNo), declarationNo, key));
    }

    @GetMapping("/biz/service-orders/{orderNo}/manual-source")
    @PreAuthorize("@perm.canBiz('" + BizPerms.ORDER_VIEW + "')")
    public ManualSourceFacts source(
            @PathVariable String orderNo, @RequestParam String operationNo) {
        return manual.sourceForOrder(
                scope(), orderNo, new SourceLookup(new OperationRef("BEAUTY", operationNo), null));
    }

    private static CheckoutScope scope() {
        return new CheckoutScope(
                "MAIN",
                BizContext.current().requireMerchantNo(),
                BizContext.requireStoreNo(),
                SecurityUtils.currentUserNo());
    }

    private static void sameKey(String header, String body) {
        if (!header.equals(body)) {
            throw BizException.of(ErrorCode.BAD_REQUEST);
        }
    }
}
