package ai.neargo.shop.beauty.port;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

import ai.neargo.shop.auth.DelegatedIdentityContext;
import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.spi.security.DelegatedIdentity;
import ai.neargo.shop.spi.security.InternalCall;
import ai.neargo.shop.spi.security.InternalReply;
import ai.neargo.shop.spi.security.InternalRequestBinding;
import ai.neargo.shop.spi.trade.CheckoutPort.CheckoutLineReq;
import ai.neargo.shop.spi.trade.CheckoutPort.CheckoutQuote;
import ai.neargo.shop.spi.trade.CheckoutPort.CheckoutResult;
import ai.neargo.shop.spi.trade.CheckoutPort.CheckoutScope;
import ai.neargo.shop.spi.trade.CheckoutPort.OperationRef;
import ai.neargo.shop.spi.trade.CheckoutPort.QuoteLine;
import ai.neargo.shop.spi.trade.ManualServiceAdmissionInternalApi;
import ai.neargo.shop.spi.trade.ManualServiceAdmissionInternalApi.Scoped;
import ai.neargo.shop.spi.trade.ManualServiceAdmissionPort.AcceptCounterReq;
import ai.neargo.shop.spi.trade.ManualServiceAdmissionPort.CounterCheckoutQuote;
import ai.neargo.shop.spi.trade.ManualServiceAdmissionPort.CounterConfirmation;
import ai.neargo.shop.spi.trade.ManualServiceAdmissionPort.CounterCustomerRef;
import ai.neargo.shop.spi.trade.ManualServiceAdmissionPort.CustomerParty;
import ai.neargo.shop.spi.trade.ManualServiceAdmissionPort.PlaceCounterReq;
import ai.neargo.shop.spi.trade.ManualServiceAdmissionPort.PrepareCounterReq;
import ai.neargo.shop.svc.InternalBindings;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/** Typed parties must retain actual identity without inventing a user or revision. */
class RemoteCounterPurchaseTest {
    @Test
    void wrongPartyQuoteOrActionIsUnavailable() {
        var bindings = new InternalBindings("task66-counter-protocol-key");
        var seen = new AtomicReference<InternalCall<?>>();
        var mutation = new AtomicInteger();
        var operation = new OperationRef("BEAUTY", "OP");
        var api =
                (ManualServiceAdmissionInternalApi)
                        Proxy.newProxyInstance(
                                ManualServiceAdmissionInternalApi.class.getClassLoader(),
                                new Class<?>[] {ManualServiceAdmissionInternalApi.class},
                                (proxy, method, args) -> {
                                    var call = (InternalCall<?>) args[0];
                                    seen.set(call);
                                    var quote =
                                            new CheckoutQuote(
                                                    operation,
                                                    "Q",
                                                    1,
                                                    "a".repeat(64),
                                                    Instant.parse("2026-10-26T00:05:00Z"),
                                                    null,
                                                    mutation.get() == 1 ? "OTHER" : "E",
                                                    "S",
                                                    "CNY",
                                                    List.of(
                                                            new QuoteLine(
                                                                    "line",
                                                                    "SKU",
                                                                    "GOODS",
                                                                    1,
                                                                    "COUNT",
                                                                    101,
                                                                    101,
                                                                    0,
                                                                    101,
                                                                    "b".repeat(64))),
                                                    101,
                                                    0,
                                                    101,
                                                    "OFFLINE",
                                                    "STORE_VERIFY",
                                                    "SERVICE_EXECUTION",
                                                    List.of(),
                                                    List.of(),
                                                    "QUOTED",
                                                    1);
                                    if (mutation.get() == 3) {
                                        var binding = call.binding();
                                        call =
                                                new InternalCall<>(
                                                        new InternalRequestBinding(
                                                                1,
                                                                binding.endpoint(),
                                                                "PREPARE",
                                                                binding.identity(),
                                                                binding.entityNo(),
                                                                binding.storeNo(),
                                                                binding.targetNo()),
                                                        call.request());
                                    }
                                    return bindings.reply(
                                            call,
                                            new CounterCheckoutQuote(
                                                    quote,
                                                    new CustomerParty(
                                                            "GUEST",
                                                            null,
                                                            mutation.get() == 2 ? "OTHER" : "G",
                                                            2L),
                                                    "MERCHANT_RECORDED_IN_PERSON"));
                                });
        var port = new RemoteManualServiceAdmissionPort(api, bindings);
        var scope = new CheckoutScope("MAIN", "E", "S", "A");
        var request =
                new PrepareCounterReq(
                        operation,
                        "key",
                        new CounterCustomerRef("GUEST", null, "G", "x".repeat(43)),
                        1,
                        "c".repeat(64),
                        List.of(new CheckoutLineReq("line", "SKU", 1)),
                        null,
                        Map.of(),
                        null);
        DelegatedIdentityContext.call(
                new DelegatedIdentity("MAIN", "MERCHANT", "MCH", "A"),
                () -> {
                    assertDoesNotThrow(() -> port.prepareCounter(scope, request));
                    assertThat(seen.get().binding().endpoint())
                            .isEqualTo("MANUAL_SERVICE_ADMISSION");
                    assertThat(seen.get().binding().action()).isEqualTo("COUNTER_PREPARE");
                    assertThat(seen.get().binding().targetNo()).isEqualTo("OP");
                    assertThat(seen.get().binding().entityNo()).isEqualTo("E");
                    assertThat(seen.get().binding().storeNo()).isEqualTo("S");
                    for (int wrong : List.of(1, 2, 3)) {
                        mutation.set(wrong);
                        assertThatThrownBy(() -> port.prepareCounter(scope, request))
                                .isInstanceOf(RuntimeException.class);
                    }
                    return null;
                });
        seen.set(null);
        DelegatedIdentityContext.call(
                new DelegatedIdentity("MAIN", "CONSUMER", "USR", "A"),
                () -> {
                    assertThatThrownBy(() -> port.prepareCounter(scope, request))
                            .isInstanceOf(RuntimeException.class);
                    return null;
                });
        assertThat(seen.get()).isNull();
    }

    @Test
    void everyCounterActionBindsEntirePayload() {
        var bindings = new InternalBindings("task66-counter-all-actions");
        var identity = new DelegatedIdentity("MAIN", "MERCHANT", "MCH", "A");
        var scope = new CheckoutScope("MAIN", "E", "S", "A");
        var operation = new OperationRef("BEAUTY", "OP");
        var now = Instant.parse("2026-10-26T00:00:00.123Z");
        var quote = quote(operation);
        var prepare =
                new PrepareCounterReq(
                        operation,
                        "prepare",
                        new CounterCustomerRef("GUEST", null, "G", "x".repeat(43)),
                        1,
                        "c".repeat(64),
                        List.of(new CheckoutLineReq("line", "SKU", 1)),
                        null,
                        Map.of(),
                        null);
        var accept =
                new AcceptCounterReq(
                        operation,
                        "accept",
                        "Q",
                        1,
                        "a".repeat(64),
                        2L,
                        now,
                        "MERCHANT_RECORDED_IN_PERSON");
        var place = new PlaceCounterReq(operation, "CC", "place");
        var replies = new LinkedHashMap<String, Object>();
        replies.put("prepareCounter", quote);
        replies.put("readCounterQuote", quote);
        replies.put(
                "acceptCounter",
                new CounterConfirmation(
                        operation,
                        "CC",
                        "Q",
                        1,
                        "a".repeat(64),
                        quote.party(),
                        "MERCHANT_RECORDED_IN_PERSON",
                        now,
                        now.plusSeconds(300),
                        "AUTHORIZED"));
        replies.put(
                "placeCounter",
                new CheckoutResult(operation, "CREATED", 3, "ORDER", "SUB", null, null, now));
        var seen = new LinkedHashMap<String, InternalCall<?>>();
        var envelopes = new LinkedHashMap<String, InternalReply<?>>();
        var api =
                (ManualServiceAdmissionInternalApi)
                        Proxy.newProxyInstance(
                                ManualServiceAdmissionInternalApi.class.getClassLoader(),
                                new Class<?>[] {ManualServiceAdmissionInternalApi.class},
                                (proxy, method, args) -> {
                                    var call = (InternalCall<?>) args[0];
                                    seen.put(method.getName(), call);
                                    var reply = bindings.reply(call, replies.get(method.getName()));
                                    envelopes.put(method.getName(), reply);
                                    return reply;
                                });
        var port = new RemoteManualServiceAdmissionPort(api, bindings);
        DelegatedIdentityContext.call(
                identity,
                () -> {
                    assertThat(port.prepareCounter(scope, prepare)).isEqualTo(quote);
                    assertThat(port.readCounterQuote(scope, operation)).isEqualTo(quote);
                    assertThat(port.acceptCounter(scope, accept).confirmationNo()).isEqualTo("CC");
                    assertThat(port.placeCounter(scope, place).orderNo()).isEqualTo("ORDER");
                    return null;
                });
        var actions =
                Map.of(
                        "prepareCounter",
                        "COUNTER_PREPARE",
                        "readCounterQuote",
                        "COUNTER_READ",
                        "acceptCounter",
                        "COUNTER_ACCEPT",
                        "placeCounter",
                        "COUNTER_PLACE");
        var bodies =
                Map.of(
                        "prepareCounter",
                        prepare,
                        "readCounterQuote",
                        operation,
                        "acceptCounter",
                        accept,
                        "placeCounter",
                        place);
        for (var entry : seen.entrySet()) {
            var call = entry.getValue();
            assertThat(call.binding())
                    .isEqualTo(
                            new InternalRequestBinding(
                                    1,
                                    "MANUAL_SERVICE_ADMISSION",
                                    actions.get(entry.getKey()),
                                    identity,
                                    "E",
                                    "S",
                                    "OP"));
            assertThat(call.request()).isEqualTo(new Scoped<>(scope, bodies.get(entry.getKey())));
        }
        var original = seen.get("prepareCounter");
        var changed =
                new PrepareCounterReq(
                        operation,
                        "prepare",
                        new CounterCustomerRef("GUEST", null, "G", "z".repeat(43)),
                        1,
                        "c".repeat(64),
                        prepare.lines(),
                        null,
                        Map.of(),
                        "changed");
        assertThatThrownBy(
                        () ->
                                bindings.require(
                                        new InternalCall<>(
                                                original.binding(), new Scoped<>(scope, changed)),
                                        envelopes.get("prepareCounter")))
                .isInstanceOf(IllegalArgumentException.class);
        var accepted = seen.get("acceptCounter");
        var changedTime =
                new AcceptCounterReq(
                        operation,
                        "accept",
                        "Q",
                        1,
                        "a".repeat(64),
                        2L,
                        now.plusMillis(1),
                        "MERCHANT_RECORDED_IN_PERSON");
        assertThatThrownBy(
                        () ->
                                bindings.require(
                                        new InternalCall<>(
                                                accepted.binding(),
                                                new Scoped<>(scope, changedTime)),
                                        envelopes.get("acceptCounter")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void currentAccountRevocationStopsCounter() {
        var bindings = new InternalBindings("task66-counter-revocation");
        var calls = new AtomicInteger();
        var operation = new OperationRef("BEAUTY", "OP");
        var api =
                (ManualServiceAdmissionInternalApi)
                        Proxy.newProxyInstance(
                                ManualServiceAdmissionInternalApi.class.getClassLoader(),
                                new Class<?>[] {ManualServiceAdmissionInternalApi.class},
                                (proxy, method, args) -> {
                                    if (calls.incrementAndGet() > 1) {
                                        throw BizException.of(ErrorCode.FORBIDDEN);
                                    }
                                    return bindings.reply(
                                            (InternalCall<?>) args[0], quote(operation));
                                });
        var port = new RemoteManualServiceAdmissionPort(api, bindings);
        var scope = new CheckoutScope("MAIN", "E", "S", "A");
        DelegatedIdentityContext.call(
                new DelegatedIdentity("MAIN", "MERCHANT", "MCH", "A"),
                () -> {
                    assertThat(port.readCounterQuote(scope, operation).party().guestNo())
                            .isEqualTo("G");
                    assertThatThrownBy(() -> port.readCounterQuote(scope, operation))
                            .isInstanceOfSatisfying(
                                    BizException.class,
                                    failure ->
                                            assertThat(failure.errorCode())
                                                    .isEqualTo(
                                                            ErrorCode
                                                                    .BEAUTY_DEPENDENCY_UNAVAILABLE));
                    return null;
                });
        assertThat(calls).hasValue(2);
        // This is reply/denial protocol coverage. Native post-member-IO account revocation uses the
        // real owner.
    }

    private static CounterCheckoutQuote quote(OperationRef operation) {
        return new CounterCheckoutQuote(
                new CheckoutQuote(
                        operation,
                        "Q",
                        1,
                        "a".repeat(64),
                        Instant.parse("2026-10-26T00:05:00.123Z"),
                        null,
                        "E",
                        "S",
                        "CNY",
                        List.of(
                                new QuoteLine(
                                        "line",
                                        "SKU",
                                        "GOODS",
                                        1,
                                        "COUNT",
                                        101,
                                        101,
                                        0,
                                        101,
                                        "b".repeat(64))),
                        101,
                        0,
                        101,
                        "OFFLINE",
                        "STORE_VERIFY",
                        "SERVICE_EXECUTION",
                        List.of(),
                        List.of(),
                        "QUOTED",
                        1),
                new CustomerParty("GUEST", null, "G", 2L),
                "MERCHANT_RECORDED_IN_PERSON");
    }

    @Test
    void typedCounterPartyPreservesRealGuestAndNullableUserRevision() {
        var guest = assertDoesNotThrow(() -> new CustomerParty("GUEST", null, "G", 7L));
        assertThat(guest.userNo()).isNull();
        assertThat(guest.guestNo()).isEqualTo("G");
        assertThat(guest.identityRevision()).isEqualTo(7);
        assertThatThrownBy(() -> new CustomerParty("GUEST", "FAKE", "G", 7L))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new CustomerParty("GUEST", null, "G", 0L))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(new CustomerParty("USER", "U", null, 1L).identityRevision()).isEqualTo(1);
    }
}
