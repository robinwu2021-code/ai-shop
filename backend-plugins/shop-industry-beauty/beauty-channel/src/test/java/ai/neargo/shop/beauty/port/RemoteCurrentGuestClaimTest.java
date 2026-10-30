package ai.neargo.shop.beauty.port;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

import ai.neargo.shop.auth.DelegatedIdentityContext;
import ai.neargo.shop.beauty.config.BeautyMerchantClientConfiguration;
import ai.neargo.shop.spi.entitlement.EntitlementContext;
import ai.neargo.shop.spi.member.CustomerIdentityInternalApi;
import ai.neargo.shop.spi.member.CustomerIdentityPort;
import ai.neargo.shop.spi.member.CustomerIdentityPort.CounterReferenceQuery;
import ai.neargo.shop.spi.member.CustomerIdentityPort.ResolvedCustomer;
import ai.neargo.shop.spi.member.GuestRegistrationInternalApi;
import ai.neargo.shop.spi.member.GuestRegistrationPort.ClaimedCustomer;
import ai.neargo.shop.spi.member.GuestRegistrationPort.ConsumerScope;
import ai.neargo.shop.spi.member.GuestRegistrationPort.CurrentClaim;
import ai.neargo.shop.spi.member.GuestRegistrationPort.GuestFact;
import ai.neargo.shop.spi.member.GuestRegistrationPort.GuestRef;
import ai.neargo.shop.spi.member.GuestRegistrationPort.Scope;
import ai.neargo.shop.spi.security.DelegatedIdentity;
import ai.neargo.shop.spi.security.InternalCall;
import ai.neargo.shop.svc.InternalBindings;
import ai.neargo.shop.svc.InternalHttp;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/** Consumer read evidence must never enter the merchant token-verification protocol. */
class RemoteCurrentGuestClaimTest {
    @Test
    void claimedGuestCounterResolutionBindsActualMemberOwner() {
        var bindings = new InternalBindings("task66-claimed-test-key");
        var seen = new AtomicReference<InternalCall<?>>();
        var wrong = new AtomicInteger();
        var api =
                (GuestRegistrationInternalApi)
                        Proxy.newProxyInstance(
                                GuestRegistrationInternalApi.class.getClassLoader(),
                                new Class<?>[] {GuestRegistrationInternalApi.class},
                                (proxy, method, args) -> {
                                    var call = (InternalCall<?>) args[0];
                                    seen.set(call);
                                    var request =
                                            (GuestRegistrationInternalApi.Verify) call.request();
                                    var returned =
                                            wrong.get() == 1
                                                    ? new GuestRegistrationInternalApi.Verify(
                                                            request.scope(),
                                                            request.guestNo(),
                                                            "z".repeat(43))
                                                    : request;
                                    var guest =
                                            new GuestFact(
                                                    wrong.get() == 2 ? "OTHER" : "G",
                                                    "CLAIMED",
                                                    "U",
                                                    2);
                                    return bindings.reply(
                                            call,
                                            new GuestRegistrationInternalApi
                                                    .ClaimedCustomerResolution(
                                                    returned,
                                                    new ClaimedCustomer(
                                                            guest, new ResolvedCustomer("U"))));
                                });
        var port = new RemoteGuestRegistrationPort(api, bindings);
        var identity = new DelegatedIdentity("MAIN", "MERCHANT", "MCH", "A");
        var scope = new Scope("E", "S", identity);
        DelegatedIdentityContext.call(
                identity,
                () -> {
                    var result =
                            assertDoesNotThrow(
                                    () -> port.resolveClaimedCustomer(scope, "G", "x".repeat(43)));
                    assertThat(result.customer().userNo()).isEqualTo("U");
                    assertThat(seen.get().binding().endpoint()).isEqualTo("GUEST_COUNTER_CUSTOMER");
                    assertThat(seen.get().binding().action()).isEqualTo("RESOLVE");
                    assertThat(seen.get().binding().targetNo()).isEqualTo("G");
                    assertThat(seen.get().binding().entityNo()).isEqualTo("E");
                    assertThat(seen.get().binding().storeNo()).isEqualTo("S");
                    for (int mutation : List.of(1, 2)) {
                        wrong.set(mutation);
                        assertThatThrownBy(
                                        () ->
                                                port.resolveClaimedCustomer(
                                                        scope, "G", "x".repeat(43)))
                                .isInstanceOf(RuntimeException.class);
                    }
                    return null;
                });
        seen.set(null);
        var consumer = new DelegatedIdentity("MAIN", "CONSUMER", "USR", "U");
        DelegatedIdentityContext.call(
                consumer,
                () -> {
                    assertThatThrownBy(
                                    () ->
                                            port.resolveClaimedCustomer(
                                                    new Scope("E", "S", consumer),
                                                    "G",
                                                    "x".repeat(43)))
                            .isInstanceOf(RuntimeException.class);
                    return null;
                });
        assertThat(seen.get()).isNull();
    }

    @Test
    void standaloneMemberAdaptersUseConfiguredBindings() throws Exception {
        var bindings = new InternalBindings("task66-config-test-key");
        var seen = new AtomicReference<InternalCall<?>>();
        var http =
                new InternalHttp(service -> Optional.empty(), "task66-config-test-key") {
                    @Override
                    public <T> T client(String service, Class<T> api, Duration timeout) {
                        return api.cast(
                                Proxy.newProxyInstance(
                                        api.getClassLoader(),
                                        new Class<?>[] {api},
                                        (proxy, method, args) -> {
                                            var call = (InternalCall<?>) args[0];
                                            seen.set(call);
                                            if (api == GuestRegistrationInternalApi.class) {
                                                var body =
                                                        (GuestRegistrationInternalApi
                                                                        .CurrentClaimsRequest)
                                                                call.request();
                                                return bindings.reply(
                                                        call,
                                                        new GuestRegistrationInternalApi
                                                                .CurrentClaimsResponse(
                                                                body, List.of()));
                                            }
                                            var body =
                                                    (CustomerIdentityInternalApi
                                                                    .CounterReferenceRequest)
                                                            call.request();
                                            return bindings.reply(
                                                    call,
                                                    new CustomerIdentityInternalApi
                                                            .CounterReferenceResolution(
                                                            body,
                                                            new CustomerIdentityPort
                                                                    .ResolvedCustomer("U")));
                                        }));
                    }
                };
        var config = new BeautyMerchantClientConfiguration();
        for (String method :
                List.of("beautyRemoteCustomerIdentityPort", "beautyRemoteGuestRegistrationPort")) {
            var factory =
                    Arrays.stream(config.getClass().getDeclaredMethods())
                            .filter(candidate -> candidate.getName().equals(method))
                            .findFirst()
                            .orElseThrow();
            assertThat(factory.getParameterCount())
                    .as("actual standalone factory must use configured token")
                    .isEqualTo(2);
            factory.setAccessible(true);
            var provider = factory.invoke(config, http, "task66-config-test-key");
            if (provider instanceof RemoteGuestRegistrationPort guests) {
                var identity = new DelegatedIdentity("MAIN", "CONSUMER", "USR", "U");
                DelegatedIdentityContext.call(
                        identity, () -> guests.currentClaims(new ConsumerScope("MAIN", identity)));
                assertThat(seen.get().binding().endpoint()).isEqualTo("GUEST_CURRENT_CLAIM");
            } else {
                assertThat(provider).isInstanceOf(RemoteCustomerIdentityPort.class);
                var identity = new DelegatedIdentity("MAIN", "MERCHANT", "MCH", "A");
                var context = new EntitlementContext("MAIN", "A", "OP", "a".repeat(64));
                DelegatedIdentityContext.call(
                        identity,
                        () ->
                                ((CustomerIdentityPort) provider)
                                        .resolveCounterReference(
                                                context, new CounterReferenceQuery("E", "S", "M")));
                assertThat(seen.get().binding().endpoint()).isEqualTo("CUSTOMER_COUNTER_REFERENCE");
            }
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    void replyForOtherClaimIsRejected() {
        var bindings = new InternalBindings("task66-protocol-test-key");
        var observed = new AtomicReference<InternalCall<?>>();
        var answer = new AtomicReference<>(new CurrentClaim("E", "S", "G", "U", 2));
        var api =
                (GuestRegistrationInternalApi)
                        Proxy.newProxyInstance(
                                GuestRegistrationInternalApi.class.getClassLoader(),
                                new Class<?>[] {GuestRegistrationInternalApi.class},
                                (proxy, method, args) -> {
                                    var call = (InternalCall<?>) args[0];
                                    observed.set(call);
                                    if (method.getName().equals("currentClaim")) {
                                        var request =
                                                (GuestRegistrationInternalApi.CurrentClaimRequest)
                                                        call.request();
                                        return bindings.reply(
                                                call,
                                                new GuestRegistrationInternalApi
                                                        .CurrentClaimResponse(
                                                        request, answer.get()));
                                    }
                                    var request =
                                            (GuestRegistrationInternalApi.CurrentClaimsRequest)
                                                    call.request();
                                    return bindings.reply(
                                            call,
                                            new GuestRegistrationInternalApi.CurrentClaimsResponse(
                                                    request, List.of(answer.get())));
                                });
        var port = new RemoteGuestRegistrationPort(api, bindings);
        var identity = new DelegatedIdentity("MAIN", "CONSUMER", "USR", "U");
        var scope = new ConsumerScope("MAIN", identity);
        var guest = new GuestRef("E", "S", "G");
        DelegatedIdentityContext.call(
                identity,
                () -> {
                    assertThat(port.currentClaim(scope, guest)).isEqualTo(answer.get());
                    assertThat(observed.get().binding().entityNo()).isNull();
                    assertThat(observed.get().binding().storeNo()).isNull();
                    assertThat(observed.get().binding().targetNo()).isEqualTo("G");
                    assertThat(port.currentClaims(scope)).containsExactly(answer.get());
                    assertThat(observed.get().binding().targetNo()).isEqualTo("U");
                    for (var wrong :
                            List.of(
                                    new CurrentClaim("OTHER", "S", "G", "U", 2),
                                    new CurrentClaim("E", "OTHER", "G", "U", 2),
                                    new CurrentClaim("E", "S", "OTHER", "U", 2),
                                    new CurrentClaim("E", "S", "G", "OTHER", 2))) {
                        answer.set(wrong);
                        assertThatThrownBy(() -> port.currentClaim(scope, guest))
                                .isInstanceOf(RuntimeException.class);
                    }
                    assertThatThrownBy(() -> port.currentClaims(scope))
                            .isInstanceOf(RuntimeException.class);
                    return null;
                });
    }

    @Test
    void consumerProofCannotUseMerchantGetVerify() {
        var calls = new AtomicInteger();
        var api =
                (GuestRegistrationInternalApi)
                        Proxy.newProxyInstance(
                                GuestRegistrationInternalApi.class.getClassLoader(),
                                new Class<?>[] {GuestRegistrationInternalApi.class},
                                (proxy, method, args) -> {
                                    calls.incrementAndGet();
                                    var fact = new GuestFact("G", "REGISTERED", null, 1);
                                    return method.getName().equals("get")
                                            ? new GuestRegistrationInternalApi.Found(
                                                    (GuestRegistrationInternalApi.Get) args[0],
                                                    fact)
                                            : new GuestRegistrationInternalApi.Verified(
                                                    (GuestRegistrationInternalApi.Verify) args[0],
                                                    fact);
                                });
        var port = new RemoteGuestRegistrationPort(api);
        var identity = new DelegatedIdentity("MAIN", "CONSUMER", "USR", "U");
        var scope = new Scope("E", "S", identity);
        DelegatedIdentityContext.call(
                identity,
                () -> {
                    assertThatThrownBy(() -> port.get(scope, "G"))
                            .isInstanceOf(RuntimeException.class);
                    assertThatThrownBy(() -> port.verify(scope, "G", "x".repeat(43)))
                            .isInstanceOf(RuntimeException.class);
                    return null;
                });
        assertThat(calls).as("consumer must fail before any merchant transport").hasValue(0);
    }
}
