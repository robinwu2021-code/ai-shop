package ai.neargo.shop.payclient;

import ai.neargo.shop.auth.*;
import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.pay.acquiring.service.MerchantPaymentQueryService.*;
import ai.neargo.shop.pay.biz.PayBizFacade.PaymentQuery;
import ai.neargo.shop.pay.client.PayBizInternalApi.PaymentSearchReq;
import ai.neargo.shop.pay.client.PortResult;
import ai.neargo.shop.spi.platform.AuditLogPort;
import ai.neargo.shop.svc.InternalHttp;
import ai.neargo.shop.svc.ServiceName;
import ai.neargo.svc.client.ServiceCalls;
import java.time.Duration;
import java.util.Objects;
import java.util.function.Function;
import java.util.function.Supplier;
import javax.sql.DataSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/** Explicit read selection is not authority: strict store identity and a source grant are required. */
@Component
@EnableConfigurationProperties(PaymentReadProperties.class)
public class PaymentReadRouter {
    public static final String HEADER = "X-Pay-Read-Environment";
    private final BizPaymentAppService production;
    private final PaymentReadProperties properties;
    private final SandboxPaymentReadApi sandbox;
    private final JdbcTemplate jdbc;
    private final BizIdentityResolver identities;
    private final AuditLogPort audit;

    @Autowired
    public PaymentReadRouter(BizPaymentAppService production, PaymentReadProperties properties,
            InternalHttp http, @Qualifier("dataSource") DataSource dataSource,
            BizIdentityResolver identities, AuditLogPort audit) {
        this(production, properties,
                http.client(ServiceName.PAY, SandboxPaymentReadApi.class, Duration.ofSeconds(5)),
                dataSource, identities, audit);
    }

    PaymentReadRouter(BizPaymentAppService production, PaymentReadProperties properties,
            SandboxPaymentReadApi sandbox, DataSource dataSource, BizIdentityResolver identities,
            AuditLogPort audit) {
        this.production = production;
        this.properties = properties;
        this.sandbox = sandbox;
        this.jdbc = new JdbcTemplate(dataSource);
        this.identities = identities;
        this.audit = audit;
    }

    public CollectStatus status(String environment) {
        return read(environment, BizPerms.PAY_ORDER_VIEW,
                () -> production.collectStatus(BizContext.requireStoreNo()),
                s -> sandbox.status(s.store(), s.operator(), caller(), credential()));
    }
    public PaymentPage orders(String environment, PaymentQuery query) {
        return read(environment, BizPerms.PAY_ORDER_VIEW,
                () -> production.paymentOrders(BizContext.requireStoreNo(), query),
                s -> sandbox.orders(s.store(), new PaymentSearchReq(s.operator(), query),
                        caller(), credential()));
    }
    public Summary summary(String environment, String from, String to) {
        return read(environment, BizPerms.PAY_STATEMENT,
                () -> production.paymentSummary(BizContext.requireStoreNo(), from, to),
                s -> sandbox.summary(s.store(), s.operator(), from, to, caller(), credential()));
    }
    public Lookup lookup(String environment, Long amount, String within, String suffix) {
        return read(environment, BizPerms.PAY_ORDER_VIEW,
                () -> production.paymentLookup(BizContext.requireStoreNo(), amount, within, suffix),
                s -> sandbox.lookup(s.store(), s.operator(), amount, within, suffix,
                        caller(), credential()));
    }
    public Detail detail(String environment, String no) {
        return read(environment, BizPerms.PAY_ORDER_VIEW,
                () -> production.paymentDetail(BizContext.requireStoreNo(), no),
                s -> sandbox.detail(s.store(), no, s.operator(), caller(), credential()));
    }

    private <T> T read(String environment, String permission, Supplier<T> normal,
            Function<Scope, PortResult<T>> scoped) {
        if (environment == null || "PRODUCTION".equals(environment)) {
            return normal.get();
        }
        if (!"SANDBOX".equals(environment)) {
            throw BizException.of(ErrorCode.BAD_REQUEST);
        }
        Scope scope = authorize(permission);
        PortResult<T> result = ServiceCalls.call(ServiceName.PAY, () -> scoped.apply(scope));
        if (result.errorCode() != null) {
            throw BizException.of(ErrorCode.valueOf(result.errorCode()),
                    result.args() == null ? new Object[0] : result.args().toArray());
        }
        audit.record("BIZ_PAY_VIEW", scope.store(), "SANDBOX payment read");
        return result.value();
    }

    private Scope authorize(String permission) {
        if (!properties.sandboxConfigured()) {
            throw BizException.of(ErrorCode.FORBIDDEN);
        }
        String store = BizContext.requireStoreAccess(BizContext.requireStoreNo());
        String entity = BizContext.requireMerchantNo();
        LoginUser user = SecurityUtils.currentUser().filter(u -> u.realm() == Realm.MERCHANT)
                .orElseThrow(() -> BizException.of(ErrorCode.FORBIDDEN));
        BizContext strict = identities.resolveStrict(user, store);
        if (!Objects.equals(store, strict.currentStoreNo())
                || !Objects.equals(entity, strict.merchantNo())
                || !strict.hasStoreAccess(store) || !strict.can(permission)) {
            throw BizException.of(ErrorCode.FORBIDDEN);
        }
        Integer matches = jdbc.queryForObject(
                "SELECT COUNT(*) FROM payment_collection_source b JOIN mch_store s"
                        + " ON s.store_no=b.store_no AND s.entity_no=b.entity_no JOIN mch_entity e"
                        + " ON e.entity_no=s.entity_no WHERE b.environment='SANDBOX'"
                        + " AND b.source_type='CHECKOUT' AND b.source_id=b.pay_app_id"
                        + " AND b.enabled=1 AND b.store_no=? AND b.entity_no=?"
                        + " AND s.deleted=0 AND e.deleted=0 AND e.market='TW'",
                Integer.class, store, entity);
        if (matches == null || matches == 0) {
            throw BizException.of(ErrorCode.FORBIDDEN);
        }
        return new Scope(store, SecurityUtils.currentUserNo());
    }
    private String caller() { return properties.dedicatedSandboxCaller(); }
    private String credential() { return properties.dedicatedSandboxCredential(); }
    private record Scope(String store, String operator) {}
}
