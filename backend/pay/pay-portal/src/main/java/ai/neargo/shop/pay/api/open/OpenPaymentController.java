package ai.neargo.shop.pay.api.open;

import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.pay.access.PayCaller;
import ai.neargo.shop.pay.acquiring.dto.CreatePaymentCmd;
import ai.neargo.shop.pay.acquiring.entity.PayOrder;
import ai.neargo.shop.pay.acquiring.service.PaymentService;
import ai.neargo.shop.pay.acquiring.service.PaymentService.StartPaymentCmd;
import ai.neargo.shop.pay.acquiring.service.PaymentService.StartPaymentResult;
import ai.neargo.shop.pay.api.auth.PortalPrincipal;
import ai.neargo.shop.pay.api.common.PortalRequests;
import ai.neargo.shop.pay.api.common.PortalResponse;
import ai.neargo.shop.pay.api.open.OpenViews.CloseReq;
import ai.neargo.shop.pay.api.open.OpenViews.CreateOrderReq;
import ai.neargo.shop.pay.api.open.OpenViews.OrderView;
import ai.neargo.shop.pay.api.open.OpenViews.StartReq;
import ai.neargo.shop.pay.merchant.MerchantDefaults;
import ai.neargo.shop.pay.merchant.service.PayMerchantBindingService;
import ai.neargo.shop.pay.merchant.service.PayMerchantBindingService.StoreRef;
import ai.neargo.shop.pay.openapp.entity.PayApp;
import ai.neargo.shop.pay.openapp.service.AppAuthService;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 开放 API · 支付单（接口清单 §2.1 O01-O05；TDD-支付服务-I5接入层 S2-S4）。调用方只从过滤器认出的
 * {@link PortalPrincipal} 来；每个端点先查 scope、再按授权动作换出 {@link PayCaller}，门店范围由收单域的调用方判定再查一次。
 */
@RestController
@RequestMapping("/open/pay/v1/orders")
public class OpenPaymentController {

    /** 建单默认 30 分钟过期，最长一天 */
    private static final int DEFAULT_EXPIRE_MINUTES = 30;
    private static final int MAX_EXPIRE_MINUTES = 1440;

    private final AppAuthService apps;
    private final PaymentService payments;
    private final PayMerchantBindingService stores;
    private final String checkoutBase;

    public OpenPaymentController(AppAuthService apps, PaymentService payments, PayMerchantBindingService stores,
                                 @Value("${shop.pay.checkout.base-url:}") String checkoutBase) {
        this.apps = apps;
        this.payments = payments;
        this.stores = stores;
        this.checkoutBase = checkoutBase;
    }

    @PostMapping
    public PortalResponse<OrderView> create(PortalPrincipal who, @RequestBody CreateOrderReq req, HttpServletRequest http) {
        PayApp app = apps.requireActive(who.subjectId());
        apps.requireScope(app, "pay:create");
        if (blank(req.outOrderNo()) || req.outOrderNo().length() > 64 || blank(req.storeRef()) || req.amountMinor() == null
                || req.amountMinor() <= 0 || blank(req.currency()) || blank(req.subject())) {
            throw BizException.of(ErrorCode.BAD_REQUEST);
        }
        int minutes = req.expireMinutes() == null ? DEFAULT_EXPIRE_MINUTES : req.expireMinutes();
        if (minutes < 1 || minutes > MAX_EXPIRE_MINUTES) {
            throw BizException.of(ErrorCode.BAD_REQUEST);
        }
        // 门店不存在与没授权回同一个 403：不让第三方用门店号探测别家门店
        StoreRef store = stores.storeByNo(req.storeRef()).orElseThrow(() -> BizException.of(ErrorCode.PAY_FORBIDDEN));
        String market = store.market();
        if (!MerchantDefaults.currencyOf(market).equals(req.currency())) {
            throw BizException.of(ErrorCode.BAD_REQUEST);
        }
        PayCaller caller = apps.callerFor(app, "COLLECT");
        PayOrder o = payments.create(caller, new CreatePaymentCmd("PARTNER",
                blank(req.bizNo()) ? req.outOrderNo() : req.bizNo(), req.outOrderNo(),
                store.storeId(), store.storeName(), store.entityName(), market, req.amountMinor(), req.currency(),
                req.subject(), "ONLINE", Instant.now().plus(minutes, ChronoUnit.MINUTES), store.fundsPath()));
        return ok(OrderView.of(o, checkoutBase), http);
    }

    @GetMapping("/{payOrderNo}")
    public PortalResponse<OrderView> get(PortalPrincipal who, @PathVariable String payOrderNo, HttpServletRequest http) {
        return ok(OrderView.of(payments.query(reader(who), payOrderNo), checkoutBase), http);
    }

    @GetMapping
    public PortalResponse<OrderView> byOutOrderNo(PortalPrincipal who, @RequestParam String outOrderNo,
                                                  HttpServletRequest http) {
        PayCaller caller = reader(who);
        PayOrder o = payments.findByOutOrderNo(caller.callerId(), outOrderNo);
        if (o == null) {
            throw BizException.of(ErrorCode.PAY_ORDER_NOT_FOUND);
        }
        return ok(OrderView.of(payments.query(caller, o.getPayOrderNo()), checkoutBase), http);
    }

    @PostMapping("/{payOrderNo}/pay")
    public PortalResponse<StartPaymentResult> start(PortalPrincipal who, @PathVariable String payOrderNo,
                                                    @RequestBody StartReq req, HttpServletRequest http) {
        PayApp app = apps.requireActive(who.subjectId());
        apps.requireScope(app, "pay:create");
        if (blank(req.payMethod())) {
            throw BizException.of(ErrorCode.BAD_REQUEST);
        }
        return ok(payments.start(apps.callerFor(app, "COLLECT"), payOrderNo,
                new StartPaymentCmd(req.payMethod(), req.payerOpenId(), req.payerAppId())), http);
    }

    @PostMapping("/{payOrderNo}/close")
    public PortalResponse<OrderView> close(PortalPrincipal who, @PathVariable String payOrderNo,
                                           @RequestBody(required = false) CloseReq req, HttpServletRequest http) {
        PayApp app = apps.requireActive(who.subjectId());
        apps.requireScope(app, "pay:create");
        String reason = req == null || blank(req.reason()) ? "PARTNER_CLOSE" : req.reason();
        return ok(OrderView.of(payments.close(apps.callerFor(app, "COLLECT"), payOrderNo, reason), checkoutBase), http);
    }

    private PayCaller reader(PortalPrincipal who) {
        PayApp app = apps.requireActive(who.subjectId());
        apps.requireScope(app, "pay:read");
        return apps.callerFor(app, "READ");
    }

    static <T> PortalResponse<T> ok(T data, HttpServletRequest http) {
        return new PortalResponse<>(PortalResponse.OK, "OK", data, PortalRequests.requestId(http));
    }

    static boolean blank(String s) {
        return s == null || s.isBlank();
    }
}
