package ai.neargo.shop.commerce.scenario;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ai.neargo.shop.commerce.port.ChannelSourceValidationPortImpl;
import ai.neargo.shop.common.BizException;
import ai.neargo.shop.spi.commerce.ChannelSourceValidationPort.Kind;
import ai.neargo.shop.spi.commerce.ChannelSourceValidationPort.Owner;
import ai.neargo.shop.spi.commerce.ChannelSourceValidationPort.ReadMode;
import ai.neargo.shop.spi.commerce.ChannelSourceValidationPort.Source;
import ai.neargo.shop.spi.commerce.ChannelSourceValidationPort.SourceContext;
import ai.neargo.shop.spi.commerce.ChannelSourceValidationPort.Status;
import ai.neargo.shop.spi.commerce.ChannelSourceValidationPort.Validation;
import ai.neargo.shop.spi.commerce.ChannelSourceValidationProvider;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

class ChannelSourceValidationRegistryTest {
    static final Instant NOW = Instant.parse("2026-10-28T00:00:00Z");
    static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

    static Source source(Owner owner) {
        return new Source("E", "S", owner, Kind.MENU, "M", "R");
    }

    static Validation valid() {
        return new Validation(
                Status.VALID, "M", "R", "a".repeat(64), NOW.plusSeconds(20), "receipt");
    }

    static ChannelSourceValidationProvider provider(
            Owner owner, Validation answer, AtomicInteger calls) {
        return new ChannelSourceValidationProvider() {
            @Override
            public Owner owner() {
                return owner;
            }

            @Override
            public Validation validate(Source source) {
                calls.incrementAndGet();
                return answer;
            }
        };
    }

    @Test
    void routesBothIndustriesAndNeverFallsBackForAMissingOwner() {
        var food = new AtomicInteger();
        var retail = new AtomicInteger();
        var first = provider(Owner.FOOD, valid(), food);
        var second = provider(Owner.RETAIL, valid(), retail);
        var both = new ChannelSourceValidationPortImpl(List.of(first, second), CLOCK);
        assertThat(both.validate(source(Owner.FOOD))).isEqualTo(valid());
        assertThat(both.validate(source(Owner.RETAIL))).isEqualTo(valid());
        assertThat(food.get()).isEqualTo(1);
        assertThat(retail.get()).isEqualTo(1);
        var one = new ChannelSourceValidationPortImpl(List.of(first), CLOCK);
        assertThat(one.validate(source(Owner.RETAIL)).status()).isEqualTo(Status.UNSUPPORTED);
        assertThat(food.get()).isEqualTo(1);
    }

    @Test
    void rejectsDuplicateAndMissingOwnersAtConstruction() {
        var provider = provider(Owner.FOOD, valid(), new AtomicInteger());
        assertThatThrownBy(
                        () ->
                                new ChannelSourceValidationPortImpl(
                                        List.of(provider, provider), CLOCK))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(
                        () ->
                                new ChannelSourceValidationPortImpl(
                                        List.of(provider(null, valid(), new AtomicInteger())),
                                        CLOCK))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void rejectsMalformedInputsBeforeCallingOwner() {
        var calls = new AtomicInteger();
        var port =
                new ChannelSourceValidationPortImpl(
                        List.of(provider(Owner.FOOD, valid(), calls)), CLOCK);
        assertThatThrownBy(() -> port.validate(null)).isInstanceOf(BizException.class);
        for (var input :
                List.of(
                        new Source(" ", "S", Owner.FOOD, Kind.MENU, "M", "R"),
                        new Source("E", "S", null, Kind.MENU, "M", "R"),
                        new Source("E", "S", Owner.FOOD, null, "M", "R"),
                        new Source("E", "S", Owner.FOOD, Kind.MENU, "M\nR", "R"))) {
            assertThatThrownBy(() -> port.validate(input)).isInstanceOf(BizException.class);
        }
        assertThat(calls.get()).isZero();
    }

    @Test
    void rejectsWrongIdentityMissingReceiptAndExpiredOrExtendedSuccess() {
        var good = valid();
        for (var bad :
                List.of(
                        new Validation(
                                Status.VALID,
                                "OTHER",
                                "R",
                                good.contentHash(),
                                good.validUntil(),
                                "receipt"),
                        new Validation(
                                Status.VALID,
                                "M",
                                "OTHER",
                                good.contentHash(),
                                good.validUntil(),
                                "receipt"),
                        new Validation(Status.VALID, "M", "R", "bad", good.validUntil(), "receipt"),
                        new Validation(Status.VALID, "M", "R", good.contentHash(), NOW, "receipt"),
                        new Validation(
                                Status.VALID,
                                "M",
                                "R",
                                good.contentHash(),
                                NOW.plusSeconds(31),
                                "receipt"),
                        new Validation(
                                Status.VALID,
                                "M",
                                "R",
                                good.contentHash(),
                                good.validUntil(),
                                null),
                        new Validation(
                                Status.VALID,
                                "M",
                                "R",
                                good.contentHash(),
                                good.validUntil(),
                                "receipt",
                                "PARENT"))) {
            var port =
                    new ChannelSourceValidationPortImpl(
                            List.of(provider(Owner.FOOD, bad, new AtomicInteger())), CLOCK);
            assertThatThrownBy(() -> port.validate(source(Owner.FOOD)))
                    .isInstanceOf(IllegalStateException.class);
        }
    }

    @Test
    void preservesOwnerErrorsAndNonSuccessWithoutCachingThemAsSuccess() {
        var failure = new IllegalStateException("transport");
        var throwing =
                new ChannelSourceValidationProvider() {
                    @Override
                    public Owner owner() {
                        return Owner.FOOD;
                    }

                    @Override
                    public Validation validate(Source source) {
                        throw failure;
                    }
                };
        var port = new ChannelSourceValidationPortImpl(List.of(throwing), CLOCK);
        assertThatThrownBy(() -> port.validate(source(Owner.FOOD))).isSameAs(failure);
        var stale = new Validation(Status.STALE, null, null, null, null, null);
        assertThat(
                        new ChannelSourceValidationPortImpl(
                                        List.of(provider(Owner.FOOD, stale, new AtomicInteger())),
                                        CLOCK)
                                .validate(source(Owner.FOOD)))
                .isEqualTo(stale);
    }

    @Test
    void legacySuccessCannotGrantExplicitParentsOrHistoricalPurpose() {
        var port =
                new ChannelSourceValidationPortImpl(
                        List.of(provider(Owner.FOOD, valid(), new AtomicInteger())), CLOCK);
        var context = new SourceContext("M", null, ReadMode.HISTORICAL);
        assertThatThrownBy(
                        () ->
                                port.validate(
                                        new Source(
                                                "E",
                                                "S",
                                                Owner.FOOD,
                                                Kind.MENU,
                                                "M",
                                                "R",
                                                null,
                                                context)))
                .isInstanceOf(IllegalStateException.class);
        var proof =
                new Validation(
                        Status.VALID,
                        "M",
                        "R",
                        "a".repeat(64),
                        NOW.plusSeconds(20),
                        "receipt",
                        null,
                        context);
        var original =
                new ChannelSourceValidationPortImpl(
                        List.of(provider(Owner.FOOD, proof, new AtomicInteger())), CLOCK);
        assertThat(
                        original.validate(
                                new Source(
                                        "E", "S", Owner.FOOD, Kind.MENU, "M", "R", null, context)))
                .isEqualTo(proof);
        assertThatThrownBy(
                        () ->
                                original.validate(
                                        new Source(
                                                "E",
                                                "S",
                                                Owner.FOOD,
                                                Kind.MENU,
                                                "M",
                                                "R",
                                                null,
                                                new SourceContext(
                                                        "OTHER", null, ReadMode.HISTORICAL))))
                .isInstanceOf(IllegalStateException.class);
    }
}
