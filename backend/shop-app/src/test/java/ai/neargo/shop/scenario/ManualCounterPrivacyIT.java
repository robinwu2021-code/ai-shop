package ai.neargo.shop.scenario;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;

import ai.neargo.shop.auth.DelegatedIdentityContext;
import ai.neargo.shop.auth.LoginUser;
import ai.neargo.shop.auth.Realm;
import ai.neargo.shop.auth.TokenStore.SessionData;
import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.member.port.MemberGuestRegistrationPortImpl;
import ai.neargo.shop.member.service.MemberGuestRegistrationService;
import ai.neargo.shop.member.service.impl.MemberGuestRegistrationServiceImpl;
import ai.neargo.shop.merchant.port.MerchantOperatorAccessPortImpl;
import ai.neargo.shop.spi.member.GuestRegistrationPort;
import ai.neargo.shop.spi.member.GuestRegistrationPort.ClaimCommand;
import ai.neargo.shop.spi.member.GuestRegistrationPort.ConsumerScope;
import ai.neargo.shop.spi.member.GuestRegistrationPort.RegisterCommand;
import ai.neargo.shop.spi.member.GuestRegistrationPort.Registration;
import ai.neargo.shop.spi.member.GuestRegistrationPort.Scope;
import ai.neargo.shop.spi.security.DelegatedIdentity;
import ai.neargo.shop.spi.trade.CheckoutPort.CheckoutLineReq;
import ai.neargo.shop.spi.trade.CheckoutPort.CheckoutResult;
import ai.neargo.shop.spi.trade.CheckoutPort.OperationRef;
import ai.neargo.shop.spi.trade.CheckoutPort.PrepareCheckoutReq;
import ai.neargo.shop.spi.trade.ManualServiceAdmissionPort;
import ai.neargo.shop.spi.trade.ManualServiceAdmissionPort.AcceptCounterReq;
import ai.neargo.shop.spi.trade.ManualServiceAdmissionPort.CounterCheckoutQuote;
import ai.neargo.shop.spi.trade.ManualServiceAdmissionPort.CounterCustomerRef;
import ai.neargo.shop.spi.trade.ManualServiceAdmissionPort.PlaceCounterReq;
import ai.neargo.shop.spi.trade.ManualServiceAdmissionPort.PrepareCounterReq;
import ai.neargo.shop.spi.user.MerchantOperatorAccessPort;
import ai.neargo.shop.spi.user.UserScopeFactsPort;
import ai.neargo.shop.trade.service.OrderService;
import ai.neargo.shop.user.port.UserScopeFactsPortImpl;

import org.junit.jupiter.api.Test;
import org.springframework.aop.support.AopUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/** Native member account ownership is checked before the Core readers may use claim evidence. */
class ManualCounterPrivacyIT extends CheckoutFlowFixture {
    @MockitoSpyBean GuestRegistrationPort guests;
    @Autowired ManualServiceAdmissionPort manual;
    @Autowired OrderService orderReader;
    @Autowired MemberGuestRegistrationService guestOwner;
    @Autowired UserScopeFactsPort userFacts;
    @Autowired MerchantOperatorAccessPort merchantAccess;

    @Test
    void memberOwnerUnavailableFailsClosed() throws Exception {
        try (var connection = dataSource.getConnection()) {
            assertThat(connection.getMetaData().getDatabaseProductName()).isEqualTo("MySQL");
        }
        actualOrigin(guests, MemberGuestRegistrationPortImpl.class);
        actualOrigin(guestOwner, MemberGuestRegistrationServiceImpl.class);
        actualOrigin(userFacts, UserScopeFactsPortImpl.class);
        actualOrigin(merchantAccess, MerchantOperatorAccessPortImpl.class);
        var scope =
                new ConsumerScope("MAIN", new DelegatedIdentity("MAIN", "CONSUMER", "USR", buyer));
        assertThat(asBuyer(() -> assertDoesNotThrow(() -> guests.currentClaims(scope)))).isEmpty();
        DelegatedIdentityContext.call(
                scope.identity(),
                () -> {
                    assertThatThrownBy(() -> guests.currentClaims(scope))
                            .as(
                                    "an active account alone cannot turn raw delegation into consumer proof")
                            .isInstanceOf(RuntimeException.class);
                    return null;
                });
        jdbc.update("UPDATE usr_account SET status='DISABLED' WHERE user_no=?", buyer);
        try {
            asBuyer(
                    () -> {
                        assertThatThrownBy(() -> guests.currentClaims(scope))
                                .isInstanceOf(RuntimeException.class);
                        return null;
                    });
        } finally {
            jdbc.update("UPDATE usr_account SET status='NORMAL' WHERE user_no=?", buyer);
        }
    }

    @Override
    protected <T> T asMerchant(Supplier<T> work) {
        return ManualServiceAdmissionIT.currentCashier(tokens, actor, work);
    }

    @Test
    void claimedOwnerReadsOriginalGuestOrder() {
        jdbc.update(
                "INSERT INTO mch_qualification(qual_no,entity_no,qual_type,qual_name,qual_number,status,"
                        + "created_at,updated_at) VALUES (?,?,'BUSINESS_LICENSE','privacy license','FIXTURE',"
                        + "'VALID',NOW(),NOW())",
                "privacy-" + sku,
                entity);
        var guestScope =
                new Scope(entity, store, new DelegatedIdentity("MAIN", "MERCHANT", "MCH", actor));
        var registration =
                asMerchant(
                        () ->
                                guests.register(
                                        guestScope,
                                        new RegisterCommand(null, null, true, "private-" + sku)));
        var quote =
                asMerchant(
                        () ->
                                manual.prepareCounter(
                                        scope,
                                        new PrepareCounterReq(
                                                new OperationRef("BEAUTY", "private-" + sku),
                                                "prepare-private-" + sku,
                                                new CounterCustomerRef(
                                                        "GUEST",
                                                        null,
                                                        registration.guestNo(),
                                                        registration.claimToken()),
                                                1,
                                                "a".repeat(64),
                                                List.of(new CheckoutLineReq("line", sku, 1)),
                                                null,
                                                Map.of(),
                                                null)));
        var accepted =
                asMerchant(
                        () ->
                                manual.acceptCounter(
                                        scope,
                                        new AcceptCounterReq(
                                                quote.quote().operation(),
                                                "accept-private-" + sku,
                                                quote.quote().quoteNo(),
                                                1,
                                                quote.quote().quoteHash(),
                                                quote.party().identityRevision(),
                                                testClock.instant().truncatedTo(ChronoUnit.MILLIS),
                                                "MERCHANT_RECORDED_IN_PERSON")));
        var created =
                asMerchant(
                        () ->
                                manual.placeCounter(
                                        scope,
                                        new PlaceCounterReq(
                                                accepted.operation(),
                                                accepted.confirmationNo(),
                                                "place-private-" + sku)));
        assertThat(created.phase()).isEqualTo("CREATED");
        asBuyer(
                () -> {
                    assertThatThrownBy(() -> orderReader.detail(created.orderNo(), null))
                            .isInstanceOf(BizException.class);
                    assertThatThrownBy(() -> orderReader.payMethods(created.orderNo()))
                            .isInstanceOf(BizException.class);
                    assertThat(orderReader.list(null, List.of(), 1, 20).records()).isEmpty();
                    return null;
                });
        asMerchant(
                () ->
                        guests.claim(
                                guestScope,
                                registration.guestNo(),
                                new ClaimCommand(
                                        reference,
                                        registration.claimToken(),
                                        "private-claim-" + sku)));
        asBuyer(
                () -> {
                    assertThat(orderReader.detail(created.orderNo(), null).orderNo())
                            .isEqualTo(created.orderNo());
                    assertThat(orderReader.detail(created.subOrderNo(), null).orderNo())
                            .isEqualTo(created.subOrderNo());
                    assertThat(orderReader.payResult(created.orderNo()).orderNo())
                            .isEqualTo(created.orderNo());
                    assertThat(orderReader.payMethods(created.orderNo()).methods()).hasSize(1);
                    assertThat(orderReader.list(null, List.of(), 1, 20).total()).isEqualTo(1);
                    return null;
                });
        jdbc.update(
                "UPDATE ord_sub_order SET guest_no='unrelated-guest' WHERE sub_order_no=?",
                created.subOrderNo());
        try {
            asBuyer(
                    () -> {
                        assertThatThrownBy(() -> orderReader.detail(created.orderNo(), null))
                                .isInstanceOf(BizException.class);
                        assertThat(orderReader.list(null, List.of(), 1, 20).total()).isZero();
                        return null;
                    });
        } finally {
            jdbc.update(
                    "UPDATE ord_sub_order SET guest_no=? WHERE sub_order_no=?",
                    registration.guestNo(),
                    created.subOrderNo());
        }
        doThrow(BizException.of(ErrorCode.CHECKOUT_DEPENDENCY_UNAVAILABLE))
                .when(guests)
                .currentClaims(any());
        asBuyer(
                () -> {
                    assertThatThrownBy(() -> orderReader.detail(created.orderNo(), null))
                            .isInstanceOfSatisfying(
                                    BizException.class,
                                    failure ->
                                            assertThat(failure.errorCode())
                                                    .isEqualTo(
                                                            ErrorCode
                                                                    .CHECKOUT_DEPENDENCY_UNAVAILABLE));
                    assertThatThrownBy(() -> orderReader.list(null, List.of(), 1, 20))
                            .isInstanceOf(BizException.class);
                    return null;
                });
        assertThat(
                        jdbc.queryForMap(
                                "SELECT customer_kind,user_no,guest_no FROM ord_order WHERE order_no=?",
                                created.orderNo()))
                .containsEntry("customer_kind", "GUEST")
                .containsEntry("user_no", null)
                .containsEntry("guest_no", registration.guestNo());
    }

    @Test
    void unclaimedAndForeignUsersCannotRead() {
        var original = unclaimedCounter("foreign-reader");
        asBuyer(
                () -> {
                    assertThatThrownBy(() -> orderReader.detail(original.created().orderNo(), null))
                            .isInstanceOf(BizException.class);
                    assertThatThrownBy(() -> orderReader.payResult(original.created().subOrderNo()))
                            .isInstanceOf(BizException.class);
                    return null;
                });
        var owner =
                new Scope(entity, store, new DelegatedIdentity("MAIN", "MERCHANT", "MCH", actor));
        asMerchant(
                () ->
                        guests.claim(
                                owner,
                                original.guest().guestNo(),
                                new ClaimCommand(
                                        reference,
                                        original.guest().claimToken(),
                                        "foreign-reader-claim-" + sku)));
        String foreign = "foreign-" + buyer;
        jdbc.update(
                "INSERT INTO usr_account(user_no,nickname,status,created_at,updated_at)"
                        + " VALUES (?,'foreign reader','NORMAL',NOW(),NOW())",
                foreign);
        consumer(
                foreign,
                () -> {
                    assertThat(orderReader.list(null, List.of(), 1, 20).records()).isEmpty();
                    assertThatThrownBy(() -> orderReader.detail(original.created().orderNo(), null))
                            .isInstanceOf(BizException.class);
                    assertThatThrownBy(
                                    () -> orderReader.payMethods(original.created().subOrderNo()))
                            .isInstanceOf(BizException.class);
                    return null;
                });
        assertThat(asBuyer(() -> orderReader.detail(original.created().orderNo(), null)).orderNo())
                .isEqualTo(original.created().orderNo());
    }

    @Test
    void nullBuyerNeverWidensList() {
        var unclaimed = unclaimedCounter("null-buyer");
        var ordinary = request(null);
        var offline =
                new PrepareCheckoutReq(
                        ordinary.operation(),
                        ordinary.requestKey(),
                        ordinary.customerRef(),
                        ordinary.scopeRevision(),
                        ordinary.scopeHash(),
                        ordinary.lines(),
                        "OFFLINE",
                        ordinary.couponNo(),
                        ordinary.activityChoices(),
                        ordinary.remark());
        var quote = asMerchant(() -> manual.prepare(scope, offline));
        var confirmation = asBuyer(() -> confirm(quote));
        var owned = asBuyer(() -> checkout.place(place(quote, confirmation)));
        var page = asBuyer(() -> orderReader.list(null, List.of(), 1, 20));
        assertThat(page.total()).isEqualTo(1);
        assertThat(page.records())
                .extracting(row -> row.orderNo())
                .containsExactly(owned.subOrderNo());
        assertThat(page.records())
                .extracting(row -> row.orderNo())
                .doesNotContain(unclaimed.created().subOrderNo());
        assertThat(
                        jdbc.queryForObject(
                                "SELECT user_no FROM ord_order WHERE order_no=?",
                                String.class,
                                unclaimed.created().orderNo()))
                .isNull();
    }

    private UnclaimedCase unclaimedCounter(String suffix) {
        jdbc.update(
                "INSERT INTO mch_qualification(qual_no,entity_no,qual_type,qual_name,qual_number,status,"
                        + "created_at,updated_at) VALUES (?,?,'BUSINESS_LICENSE','privacy license','FIXTURE',"
                        + "'VALID',NOW(),NOW())",
                "privacy-" + sku,
                entity);
        var owner =
                new Scope(entity, store, new DelegatedIdentity("MAIN", "MERCHANT", "MCH", actor));
        var guest =
                asMerchant(
                        () ->
                                guests.register(
                                        owner,
                                        new RegisterCommand(null, null, true, suffix + sku)));
        var quote =
                asMerchant(
                        () ->
                                manual.prepareCounter(
                                        scope,
                                        new PrepareCounterReq(
                                                new OperationRef("BEAUTY", suffix + '-' + sku),
                                                "prepare-" + suffix + sku,
                                                new CounterCustomerRef(
                                                        "GUEST",
                                                        null,
                                                        guest.guestNo(),
                                                        guest.claimToken()),
                                                1,
                                                "a".repeat(64),
                                                List.of(new CheckoutLineReq("line", sku, 1)),
                                                null,
                                                Map.of(),
                                                null)));
        var accepted =
                asMerchant(
                        () ->
                                manual.acceptCounter(
                                        scope,
                                        new AcceptCounterReq(
                                                quote.quote().operation(),
                                                "accept-" + suffix + sku,
                                                quote.quote().quoteNo(),
                                                1,
                                                quote.quote().quoteHash(),
                                                quote.party().identityRevision(),
                                                testClock.instant().truncatedTo(ChronoUnit.MILLIS),
                                                "MERCHANT_RECORDED_IN_PERSON")));
        var created =
                asMerchant(
                        () ->
                                manual.placeCounter(
                                        scope,
                                        new PlaceCounterReq(
                                                accepted.operation(),
                                                accepted.confirmationNo(),
                                                "place-" + suffix + sku)));
        assertThat(created.phase()).isEqualTo("CREATED");
        return new UnclaimedCase(guest, quote, created);
    }

    private <T> T consumer(String userNo, Supplier<T> action) {
        var user = LoginUser.consumer(userNo, userNo);
        var pool = tokens.of(Realm.CONSUMER);
        String token = pool.issue(new SessionData(user));
        var previous = SecurityContextHolder.getContext();
        var context = SecurityContextHolder.createEmptyContext();
        var authenticated =
                UsernamePasswordAuthenticationToken.authenticated(user, null, List.of());
        authenticated.setDetails(token);
        context.setAuthentication(authenticated);
        SecurityContextHolder.setContext(context);
        try {
            return action.get();
        } finally {
            SecurityContextHolder.setContext(previous);
            pool.revoke(token);
        }
    }

    private record UnclaimedCase(
            Registration guest, CounterCheckoutQuote quote, CheckoutResult created) {}

    private static void actualOrigin(Object bean, Class<?> expected) {
        var actual = AopUtils.getTargetClass(bean);
        assertThat(actual).isEqualTo(expected);
        String origin = actual.getProtectionDomain().getCodeSource().getLocation().toString();
        assertThat(origin).contains("/beauty-data/ai-shop-codex/").contains("/target/classes/");
        System.out.println("TASK66_CODE_SOURCE " + actual.getName() + " " + origin);
    }
}
