package ai.neargo.shop.portal.biz;

import ai.neargo.shop.auth.BizContext;
import ai.neargo.shop.auth.SecurityUtils;
import ai.neargo.shop.paybridge.PaymentCollectionStream;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@RestController
@ConditionalOnProperty(name = "shop.payment-collections.enabled", havingValue = "true")
public class BizPaymentEventsController {
    private final PaymentCollectionStream stream;

    public BizPaymentEventsController(PaymentCollectionStream stream) {
        this.stream = stream;
    }

    @GetMapping(value = "/biz/payment/events", produces = "text/event-stream")
    @PreAuthorize("@perm.canBiz('mch:pay:order:view')")
    public SseEmitter events(
            @RequestHeader("Authorization") String authorization,
            @RequestHeader(value = "Last-Event-ID", required = false) String lastId,
            HttpServletResponse response,
            HttpServletRequest request) {
        response.setHeader("Cache-Control", "no-cache, no-transform");
        response.setHeader("X-Accel-Buffering", "no");
        return stream.open(
                SecurityUtils.currentUser().orElseThrow(),
                BizContext.current(),
                authorization.substring(7),
                lastId,
                response::isCommitted,
                () -> {
                    if (request.isAsyncStarted()) request.getAsyncContext().complete();
                });
    }
}
