package ai.neargo.shop.food.commerce;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import ai.neargo.shop.food.commerce.port.FoodStoreBusinessAvailabilitySource;
import ai.neargo.shop.food.dto.StoreCalendarSourceContracts;
import ai.neargo.shop.food.policy.StoreCalendarSourcePolicy;
import ai.neargo.shop.food.service.StoreCalendarSourceService;
import ai.neargo.shop.spi.commerce.ChannelAvailabilitySourcePort.Kind;
import ai.neargo.shop.spi.commerce.ChannelAvailabilitySourcePort.Query;
import ai.neargo.shop.spi.commerce.ChannelAvailabilitySourcePort.State;
import ai.neargo.shop.spi.commerce.ChannelAvailabilitySourcePort.StoreMode;
import ai.neargo.shop.spi.commerce.ChannelSourceValidationPort.Owner;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

class FoodStoreBusinessAvailabilitySourceTest {
    final Instant now = Instant.parse("2026-10-29T00:30:00Z");
    final StoreCalendarSourceService calendars = mock(StoreCalendarSourceService.class);
    final FoodStoreBusinessAvailabilitySource source =
            new FoodStoreBusinessAvailabilitySource(calendars, Clock.fixed(now, ZoneOffset.UTC));

    void calendar(String status, String hours, boolean closedException, boolean futureZone) {
        when(calendars.capture(any()))
                .thenAnswer(
                        a -> {
                            StoreCalendarSourceContracts.Query q = a.getArgument(0);
                            var policies =
                                    futureZone
                                            ? List.of(
                                                    new StoreCalendarSourceContracts.Policy(
                                                            "P1",
                                                            "UTC",
                                                            240,
                                                            now.minusSeconds(86400 * 10),
                                                            1L),
                                                    new StoreCalendarSourceContracts.Policy(
                                                            "P2",
                                                            "Asia/Shanghai",
                                                            240,
                                                            now.plusSeconds(10),
                                                            1L))
                                            : List.of(
                                                    new StoreCalendarSourceContracts.Policy(
                                                            "P1",
                                                            "UTC",
                                                            240,
                                                            now.minusSeconds(86400 * 10),
                                                            1L));
                            var exceptions =
                                    closedException
                                            ? List.of(
                                                    new StoreCalendarSourceContracts.ExceptionDay(
                                                            "EX",
                                                            now.atOffset(ZoneOffset.UTC)
                                                                    .toLocalDate()
                                                                    .minusDays(1),
                                                            "CLOSED",
                                                            List.of(),
                                                            "P1",
                                                            1L))
                                            : List.<StoreCalendarSourceContracts.ExceptionDay>of();
                            return StoreCalendarSourcePolicy.seal(
                                    new StoreCalendarSourceContracts.Facts(
                                            1,
                                            "STORE_CALENDAR",
                                            q,
                                            new StoreCalendarSourceContracts.Store(
                                                    "E", "S", status, hours),
                                            policies,
                                            exceptions,
                                            now,
                                            now.plusSeconds(25),
                                            null));
                        });
    }

    Query query(StoreMode mode) {
        return new Query("E", "S", Kind.STORE, "S", Owner.FOOD, mode);
    }

    @Test
    void physicalOpeningAndFoodOrderAcceptanceKeepTheirOwnOriginalMeanings() {
        calendar("ACTIVE", "22:00-02:00", true, false);
        var physical = source.capture(query(StoreMode.PHYSICAL_OPEN));
        var accepting = source.capture(query(StoreMode.ACCEPTING_ORDERS));
        assertThat(physical.data().state()).isEqualTo(State.OPEN);
        assertThat(accepting.data().state()).isEqualTo(State.SUSPENDED);
        assertThat(physical.data().nextChangeAt()).isEqualTo(Instant.parse("2026-10-29T02:00:00Z"));
        assertThat(accepting.data().quantity()).isNull();
        assertThat(accepting.data().unit()).isNull();
    }

    @Test
    void actualFuturePolicyCutoverBoundsBothStateDeadlineAndRecoveryBoundary() {
        calendar("ACTIVE", "22:00-02:00", false, true);
        var facts = source.capture(query(StoreMode.PHYSICAL_OPEN));
        assertThat(facts.data().state()).isEqualTo(State.OPEN);
        assertThat(facts.data().nextChangeAt()).isEqualTo(now.plusSeconds(10));
        assertThat(facts.validUntil()).isEqualTo(now.plusSeconds(10));
    }

    @Test
    void unknownHoursNeverBecomeAlwaysOpenAndReadonlyNeverBecomesAStockZero() {
        calendar("ACTIVE", "Call us", false, false);
        assertThat(source.capture(query(StoreMode.PHYSICAL_OPEN)).data().state())
                .isEqualTo(State.UNKNOWN);
        calendar("READONLY", null, false, false);
        var facts = source.capture(query(StoreMode.ACCEPTING_ORDERS));
        assertThat(facts.data().state()).isEqualTo(State.SUSPENDED);
        assertThat(facts.data().quantity()).isNull();
    }
}
