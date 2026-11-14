package ai.neargo.shop.scenario;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ai.neargo.shop.ShopApplication;
import ai.neargo.shop.auth.DelegatedIdentityContext;
import ai.neargo.shop.auth.LoginUser;
import ai.neargo.shop.auth.Realm;
import ai.neargo.shop.auth.TokenStore.SessionData;
import ai.neargo.shop.auth.TokenStores;
import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.member.dto.MemberCodeScope;
import ai.neargo.shop.member.service.MemberCodeResolveService;
import ai.neargo.shop.product.service.StoreSalesCatalogService;
import ai.neargo.shop.spi.product.StoreSalesCatalogPort;
import ai.neargo.shop.spi.security.DelegatedIdentity;
import ai.neargo.shop.spi.trade.CheckoutPort;

import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.ActiveProfiles;

import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;

import javax.sql.DataSource;

@SpringBootTest(
        classes = {ShopApplication.class, CheckoutFlowFixture.ClockConfig.class},
        properties = {
            "spring.datasource.url=${CHECKOUT_NATIVE_MYSQL_URL:jdbc:h2:mem:checkout-pricing;"
                    + "MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1}",
            "shop.member-code.enabled=true",
            "shop.catalog.publication.archive-only=true",
            "shop.member.guest.credential-key=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA="
        })
@ActiveProfiles("test")
abstract class CheckoutFlowFixture {
    @Autowired protected CheckoutPort checkout;
    @Autowired protected TokenStores tokens;
    @Autowired protected JdbcTemplate jdbc;
    @Autowired protected StoreSalesCatalogService catalogs;
    @Autowired protected MemberCodeResolveService identities;
    @Autowired protected MutableClock testClock;
    @Autowired protected DataSource dataSource;
    protected String entity;
    protected String store;
    protected String actor;
    protected String buyer;
    protected String sku;
    protected String goods;
    protected CheckoutPort.CheckoutScope scope;
    protected String reference;

    @BeforeEach
    void fixture() {
        testClock.at = Instant.now();
        String id = UUID.randomUUID().toString().substring(0, 8);
        entity = "QE-" + id;
        store = "QS-" + id;
        actor = "QA-" + id;
        buyer = "QU-" + id;
        goods = "QG-" + id;
        sku = "QK-" + id;
        jdbc.update(
                "INSERT INTO mch_entity(entity_no,name,status,created_at,updated_at) "
                        + "VALUES (?,'quote owner','ACTIVE',NOW(),NOW())",
                entity);
        jdbc.update(
                "INSERT INTO mch_store(entity_no,store_no,name,status,created_at,updated_at) "
                        + "VALUES (?,?,'quote store','ACTIVE',NOW(),NOW())",
                entity,
                store);
        jdbc.update(
                "INSERT INTO"
                        + " mch_account(entity_no,mch_account_no,user_no,is_owner,status,created_at,updated_at)"
                        + " VALUES (?,?,?,1,'ACTIVE',NOW(),NOW())",
                entity,
                actor,
                actor + "-user");
        jdbc.update(
                "INSERT INTO usr_account(user_no,nickname,status,created_at,updated_at) "
                        + "VALUES (?,'quote buyer','NORMAL',NOW(),NOW())",
                buyer);
        jdbc.update(
                "INSERT INTO"
                        + " prd_goods(goods_no,entity_no,store_no,title,type,on_sale,audit_status,"
                          + "fulfillments,duration_min,created_at,updated_at)"
                        + " VALUES (?,?,?,'Ordinary"
                        + " service','SERVICE',1,'APPROVED','[\"STORE_VERIFY\"]',30,NOW(),NOW())",
                goods,
                entity,
                store);
        jdbc.update(
                "INSERT INTO prd_sku(sku_no,goods_no,entity_no,market,price,stock,locked_stock,"
                        + "created_at,updated_at) VALUES (?,?,?,'CN',101,100,0,NOW(),NOW())",
                sku,
                goods,
                entity);
        scope = new CheckoutPort.CheckoutScope("MAIN", entity, store, actor);
        asMerchant(
                () ->
                        catalogs.publish(
                                entity,
                                store,
                                actor,
                                new StoreSalesCatalogPort.PublishReq(
                                        0L,
                                        OffsetDateTime.now(ZoneOffset.UTC).minusMinutes(1),
                                        "UTC",
                                        List.of(),
                                        List.of(
                                                new StoreSalesCatalogPort.Entry(
                                                        sku,
                                                        null,
                                                        0,
                                                        true,
                                                        StoreSalesCatalogPort.SalesMode
                                                                .UNRESTRICTED,
                                                        List.of()))),
                                "catalog-" + id));
        String code = identities.issue("MAIN", buyer).memberCode();
        reference =
                asMerchant(
                                () ->
                                        identities.resolve(
                                                new MemberCodeScope("MAIN", entity, store, actor),
                                                code))
                        .customerRef();
    }

    protected CheckoutPort.BuyerConfirmation confirm(CheckoutPort.CheckoutQuote q) {
        return checkout.confirm(
                new CheckoutPort.ConfirmCheckoutReq(
                        q.operation(),
                        q.quoteNo(),
                        q.quoteRevision(),
                        q.quoteHash(),
                        "confirm-" + sku));
    }

    protected CheckoutPort.PlaceCheckoutReq place(
            CheckoutPort.CheckoutQuote q, CheckoutPort.BuyerConfirmation c) {
        return new CheckoutPort.PlaceCheckoutReq(q.operation(), c.confirmationNo(), "place-" + sku);
    }

    protected <T> T asBuyer(Supplier<T> action) {
        var user = LoginUser.consumer(buyer, "checkout buyer");
        var pool = tokens.of(Realm.CONSUMER);
        String token = pool.issue(new SessionData(user));
        var context = SecurityContextHolder.createEmptyContext();
        var auth = UsernamePasswordAuthenticationToken.authenticated(user, null, List.of());
        auth.setDetails(token);
        context.setAuthentication(auth);
        var original = SecurityContextHolder.getContext();
        SecurityContextHolder.setContext(context);
        try {
            return action.get();
        } finally {
            SecurityContextHolder.setContext(original);
            pool.revoke(token);
        }
    }

    @TestConfiguration
    static class ClockConfig {
        @Bean("checkoutClock")
        MutableClock checkoutClock() {
            return new MutableClock();
        }
    }

    static class MutableClock extends Clock {
        volatile Instant at = Instant.now();

        public Instant instant() {
            return at;
        }

        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        public Clock withZone(ZoneId zone) {
            return this;
        }
    }

    protected CheckoutPort.PrepareCheckoutReq request(String coupon) {
        return new CheckoutPort.PrepareCheckoutReq(
                new CheckoutPort.OperationRef("BEAUTY", "op-" + sku),
                "prepare-" + sku,
                reference,
                1,
                "a".repeat(64),
                List.of(new CheckoutPort.CheckoutLineReq("service-line", sku, 3)),
                "ONLINE",
                coupon,
                Map.of(),
                null);
    }

    protected String promoCoupon(String scopeType, int minQty, int times) {
        String template = "CT-" + sku;
        String instance = "CI-" + sku;
        jdbc.update(
                "INSERT INTO"
                        + " pmt_coupon(coupon_no,entity_no,title,benefit_mode,benefit_value,scope_type,"
                          + "min_qty,times_total,created_at,updated_at)"
                        + " VALUES (?,?,'ordinary coupon','CASH',23,?,?,?,NOW(),NOW())",
                template,
                entity,
                scopeType,
                minQty,
                times);
        jdbc.update(
                "INSERT INTO"
                        + " pmt_user_coupon(user_coupon_no,coupon_no,user_no,entity_no,received_at,"
                          + "expire_at,created_at,updated_at)"
                        + " VALUES (?,?,?,?,?,?,NOW(),NOW())",
                instance,
                template,
                buyer,
                entity,
                System.currentTimeMillis(),
                System.currentTimeMillis() + 600000);
        return instance;
    }

    protected void unsupported(Supplier<?> action) {
        assertThatThrownBy(() -> asMerchant(action))
                .isInstanceOfSatisfying(
                        BizException.class,
                        error ->
                                assertThat(error.errorCode())
                                        .isEqualTo(ErrorCode.CHECKOUT_UNSUPPORTED_SELECTION));
        assertThat(
                        jdbc.queryForObject(
                                "SELECT COUNT(*) FROM ord_checkout_operation WHERE operation_no=?",
                                Integer.class,
                                "op-" + sku))
                .isZero();
    }

    protected <T> T asMerchant(Supplier<T> work) {
        return DelegatedIdentityContext.call(
                new DelegatedIdentity("MAIN", "MERCHANT", "MCH", actor), work);
    }

    protected long count(String table) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Long.class);
    }
}
