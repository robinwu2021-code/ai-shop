package ai.neargo.shop.pay.checkout.service.impl;

import ai.neargo.common.data.scope.DataScopeContext;
import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.BizKey;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.pay.access.PayCaller;
import ai.neargo.shop.pay.acquiring.dto.CreatePaymentCmd;
import ai.neargo.shop.pay.acquiring.entity.PayOrder;
import ai.neargo.shop.pay.acquiring.port.InternalCallers;
import ai.neargo.shop.pay.acquiring.route.RouteSelectionPolicy;
import ai.neargo.shop.pay.acquiring.service.PaymentService;
import ai.neargo.shop.pay.acquiring.service.PaymentService.StartPaymentCmd;
import ai.neargo.shop.pay.acquiring.service.PaymentService.StartPaymentResult;
import ai.neargo.shop.pay.acquiring.statemachine.PayOrderStatus;
import ai.neargo.shop.pay.checkout.entity.PayCheckoutSession;
import ai.neargo.shop.pay.checkout.entity.PayQrCode;
import ai.neargo.shop.pay.checkout.service.CheckoutService;
import ai.neargo.shop.pay.mapper.checkout.CheckoutMappers.PayCheckoutSessionMapper;
import ai.neargo.shop.pay.mapper.checkout.CheckoutMappers.PayQrCodeMapper;
import ai.neargo.shop.pay.mapper.openapp.OpenAppMappers.PayAppMapper;
import ai.neargo.shop.pay.market.MarketService;
import ai.neargo.shop.pay.merchant.MerchantDefaults;
import ai.neargo.shop.pay.merchant.service.PayMerchantBindingService;
import ai.neargo.shop.pay.merchant.service.PayMerchantBindingService.StoreRef;
import ai.neargo.shop.pay.openapp.SignatureCanonicalizer;
import ai.neargo.shop.pay.openapp.entity.PayApp;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/** {@link CheckoutService} 的实现（TDD-支付服务-I5接入层 S7） */
@Service
public class CheckoutServiceImpl implements CheckoutService {

    /** 静态码建的单归这个内部接入方（db/pay V11 种子） */
    static final String CHECKOUT_APP = "CHECKOUT";
    static final String QR_BIZ_TYPE = "CHECKOUT_QR";
    /** 商家代客收款建的单 */
    static final String COLLECT_BIZ_TYPE = "BIZ_COLLECT";
    private static final SecureRandom RANDOM = new SecureRandom();

    private final PayCheckoutSessionMapper sessions;
    private final PayQrCodeMapper codes;
    private final PayAppMapper apps;
    private final PaymentService payments;
    private final RouteSelectionPolicy routes;
    private final PayMerchantBindingService stores;
    private final Duration ttl;
    private final MarketService markets;

    public CheckoutServiceImpl(PayCheckoutSessionMapper sessions, PayQrCodeMapper codes, PayAppMapper apps,
                               PaymentService payments, RouteSelectionPolicy routes, PayMerchantBindingService stores,
                               @Value("${shop.pay.checkout.token-ttl:PT15M}") Duration ttl, MarketService markets) {
        this.markets = markets;
        this.sessions = sessions;
        this.codes = codes;
        this.apps = apps;
        this.payments = payments;
        this.routes = routes;
        this.stores = stores;
        this.ttl = ttl;
    }

    @Override
    public Issued issue(PayOrder o) {
        byte[] raw = new byte[32];
        RANDOM.nextBytes(raw);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(raw);
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        LocalDateTime until = now.plus(ttl);
        if (o.getExpireAt() != null && o.getExpireAt().isBefore(until)) {
            until = o.getExpireAt();
        }
        PayCheckoutSession s = new PayCheckoutSession();
        s.setTokenSha256(hash(token));
        s.setPayOrderId(o.getId());
        s.setStatus("OPEN");
        s.setExpireAt(until);
        DataScopeContext.executeWithoutScope(() -> sessions.insert(s));
        return new Issued(o.getPayOrderNo(), token, epoch(until));
    }

    @Override
    public SessionView view(String token) {
        PayCheckoutSession s = usable(token);
        PayOrder o = orderOf(s);
        boolean done = paid(o);
        List<String> methods = done ? List.of() : routes.available(
                o.getStoreId(), o.getMarket(), o.getCurrency(), o.getAmountMinor()).stream()
                .map(x -> x.product().getPayMethod()).distinct().toList();
        return new SessionView(o.getPayOrderNo(), o.getEntityNameSnapshot(), o.getStoreNameSnapshot(),
                o.getAmountMinor(), o.getCurrency(), scaleOf(o.getMarket()), o.getMarket(), done ? "DONE" : s.getStatus(),
                methods, epoch(s.getExpireAt()));
    }

    @Override
    public StartPaymentResult pay(String token, String payMethod, String payerOpenId, String payerAppId) {
        if (payMethod == null || payMethod.isBlank()) {
            throw BizException.of(ErrorCode.BAD_REQUEST);
        }
        PayCheckoutSession s = usable(token);
        PayOrder o = orderOf(s);
        if (paid(o)) {
            throw BizException.of(ErrorCode.PAY_CHECKOUT_EXPIRED);
        }
        PayCaller caller = InternalCallers.checkoutVisitor(checkoutApp().getId(), o.getStoreId());
        StartPaymentResult r = payments.start(caller, o.getPayOrderNo(), new StartPaymentCmd(payMethod, payerOpenId,
                payerAppId));
        if ("OPEN".equals(s.getStatus())) {
            PayCheckoutSession patch = new PayCheckoutSession();
            patch.setId(s.getId());
            patch.setStatus("PAYING");
            DataScopeContext.executeWithoutScope(() -> sessions.updateById(patch));
        }
        return r;
    }

    @Override
    public String result(String token) {
        PayCheckoutSession s = find(token);
        PayOrder o = orderOf(s);
        if (paid(o)) {
            if (!"DONE".equals(s.getStatus())) {
                PayCheckoutSession patch = new PayCheckoutSession();
                patch.setId(s.getId());
                patch.setStatus("DONE");
                DataScopeContext.executeWithoutScope(() -> sessions.updateById(patch));
            }
            return "SUCCEEDED";
        }
        return PayOrderStatus.CLOSED.name().equals(o.getStatus()) ? "FAILED" : "PROCESSING";
    }

    @Override
    public CodeView code(String qrCode) {
        PayQrCode c = activeCode(qrCode);
        StoreRef store = stores.storeById(c.getStoreId()).orElseThrow(() -> BizException.of(ErrorCode.NOT_FOUND));
        return new CodeView(store.entityName(), store.storeName(), c.getKind(), c.getTableRef(), c.getCurrency(),
                scaleOf(store.market()), store.market(), c.getSingleLimitMinor());
    }

    @Override
    public Issued orderByCode(String qrCode, long amountMinor) {
        PayQrCode c = activeCode(qrCode);
        if (amountMinor <= 0) {
            throw BizException.of(ErrorCode.BAD_REQUEST);
        }
        if (amountMinor > c.getSingleLimitMinor()) {
            throw BizException.of(ErrorCode.PAY_CHECKOUT_LIMIT);
        }
        LocalDateTime dayStart = LocalDate.now(ZoneOffset.UTC).atStartOfDay();
        long today = codes.dailyTotal(QR_BIZ_TYPE, c.getQrCode(), dayStart);
        if (today + amountMinor > c.getDailyLimitMinor()) {
            throw BizException.of(ErrorCode.PAY_CHECKOUT_LIMIT);
        }
        StoreRef store = stores.storeById(c.getStoreId()).orElseThrow(() -> BizException.of(ErrorCode.NOT_FOUND));
        if (!MerchantDefaults.currencyOf(store.market()).equals(c.getCurrency())) {
            // 码的币种与门店市场对不上是配置错，不是顾客的错；不建单
            throw new IllegalStateException("静态码 " + c.getId() + " 的币种与门店市场不符");
        }
        PayCaller caller = InternalCallers.checkoutVisitor(checkoutApp().getId(), store.storeId());
        PayOrder o = payments.create(caller, new CreatePaymentCmd(QR_BIZ_TYPE, c.getQrCode(), BizKey.next("QRO"),
                store.storeId(), store.storeName(), store.entityName(), store.market(), amountMinor, c.getCurrency(),
                store.storeName(), "ONLINE", Instant.now().plus(ttl), store.fundsPath()));
        return issue(o);
    }

    /** 币种小数位按市场表；市场没配给 null，不兜底成 2 位（与 {@code MarketService#currencyOf} 同一条理由） */
    private Integer scaleOf(String market) {
        return markets.find(market).map(MarketService.MarketRow::currencyScale).orElse(null);
    }

    @Override
    public Issued collect(PayCaller caller, Long storeId, long amountMinor, String subject, Integer expireMinutes) {
        int minutes = expireMinutes == null ? 15 : expireMinutes;
        if (amountMinor <= 0 || minutes < 1 || minutes > 1440) {
            throw BizException.of(ErrorCode.BAD_REQUEST);
        }
        StoreRef store = stores.storeById(storeId).orElseThrow(() -> BizException.of(ErrorCode.NOT_FOUND));
        String currency = MerchantDefaults.currencyOf(store.market());
        String outOrderNo = BizKey.next("BZC");
        PayOrder o = payments.create(caller, new CreatePaymentCmd(COLLECT_BIZ_TYPE, outOrderNo, outOrderNo, store.storeId(),
                store.storeName(), store.entityName(), store.market(), amountMinor, currency,
                subject == null || subject.isBlank() ? store.storeName() : subject, "ONLINE",
                Instant.now().plus(Duration.ofMinutes(minutes)), store.fundsPath()));
        return issue(o);
    }

    private PayQrCode activeCode(String qrCode) {
        PayQrCode c = qrCode == null ? null : DataScopeContext.executeWithoutScope(() -> codes.selectOne(
                Wrappers.<PayQrCode>lambdaQuery().eq(PayQrCode::getQrCode, qrCode)));
        if (c == null || !"ACTIVE".equals(c.getStatus())) {
            // 停用与不存在同一个回答：不让人拿码值试探
            throw BizException.of(ErrorCode.NOT_FOUND);
        }
        return c;
    }

    private PayCheckoutSession find(String token) {
        PayCheckoutSession s = token == null || token.length() > 128 ? null : DataScopeContext.executeWithoutScope(
                () -> sessions.selectOne(Wrappers.<PayCheckoutSession>lambdaQuery()
                        .eq(PayCheckoutSession::getTokenSha256, hash(token))));
        if (s == null) {
            throw BizException.of(ErrorCode.NOT_FOUND);
        }
        return s;
    }

    /** 能操作的令牌：未作废、未过期、单没关；到期的顺手记成 EXPIRED */
    private PayCheckoutSession usable(String token) {
        PayCheckoutSession s = find(token);
        if ("REVOKED".equals(s.getStatus()) || "EXPIRED".equals(s.getStatus())) {
            throw BizException.of(ErrorCode.PAY_CHECKOUT_EXPIRED);
        }
        if (s.getExpireAt().isBefore(LocalDateTime.now(ZoneOffset.UTC)) && !"DONE".equals(s.getStatus())) {
            PayCheckoutSession patch = new PayCheckoutSession();
            patch.setId(s.getId());
            patch.setStatus("EXPIRED");
            DataScopeContext.executeWithoutScope(() -> sessions.updateById(patch));
            throw BizException.of(ErrorCode.PAY_CHECKOUT_EXPIRED);
        }
        if (PayOrderStatus.CLOSED.name().equals(orderOf(s).getStatus())) {
            throw BizException.of(ErrorCode.PAY_CHECKOUT_EXPIRED);
        }
        return s;
    }

    private PayOrder orderOf(PayCheckoutSession s) {
        PayOrder o = payments.findById(s.getPayOrderId());
        if (o == null) {
            throw BizException.of(ErrorCode.NOT_FOUND);
        }
        return o;
    }

    private static boolean paid(PayOrder o) {
        String st = o.getStatus();
        return PayOrderStatus.SUCCEEDED.name().equals(st) || PayOrderStatus.PARTIALLY_REFUNDED.name().equals(st)
                || PayOrderStatus.REFUNDED.name().equals(st);
    }

    private PayApp checkoutApp() {
        PayApp app = DataScopeContext.executeWithoutScope(() -> apps.selectOne(Wrappers.<PayApp>lambdaQuery()
                .eq(PayApp::getAppNo, CHECKOUT_APP)));
        if (app == null || !"ACTIVE".equals(app.getStatus())) {
            throw new IllegalStateException("缺少收银台接入方 CHECKOUT（db/pay V11 种子）");
        }
        return app;
    }

    static String hash(String token) {
        return SignatureCanonicalizer.sha256Hex(token.getBytes(StandardCharsets.UTF_8));
    }

    private static long epoch(LocalDateTime t) {
        return t.toInstant(ZoneOffset.UTC).toEpochMilli();
    }
}
