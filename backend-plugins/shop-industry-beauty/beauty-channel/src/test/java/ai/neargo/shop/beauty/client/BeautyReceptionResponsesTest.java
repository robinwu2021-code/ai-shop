package ai.neargo.shop.beauty.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertAll;

import ai.neargo.shop.beauty.dto.ArrivalChallengeVO;
import ai.neargo.shop.beauty.dto.CustomerIdentityVO;
import ai.neargo.shop.beauty.dto.GuestClaimVO;
import ai.neargo.shop.beauty.dto.GuestRegistrationVO;
import ai.neargo.shop.beauty.dto.MyWorkOrderVO;
import ai.neargo.shop.beauty.dto.OperationVO;
import ai.neargo.shop.beauty.dto.SourceBillVO;
import ai.neargo.shop.beauty.dto.SourceObservationVO;
import ai.neargo.shop.beauty.dto.WorkOrderBillVO;
import ai.neargo.shop.beauty.dto.WorkOrderDetailVO;
import ai.neargo.shop.beauty.dto.WorkOrderTaskSnapshotVO;
import ai.neargo.shop.beauty.dto.WorkOrderTaskVO;
import ai.neargo.shop.beauty.dto.WorkOrderVO;
import ai.neargo.shop.beauty.spi.BeautyReceptionInternalApi;
import ai.neargo.shop.spi.security.DelegatedIdentity;
import ai.neargo.shop.spi.security.InternalCall;
import ai.neargo.shop.spi.security.InternalRequestBinding;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.stream.Stream;

/** Actual beauty DTOs preserve historical values while rejecting invented transport success. */
class BeautyReceptionResponsesTest {
    private static InternalCall<?> call(String action, String target) {
        return new InternalCall<>(
                new InternalRequestBinding(
                        1,
                        "TEST",
                        action,
                        new DelegatedIdentity("MAIN", "MERCHANT", "USR", "actor"),
                        "E",
                        "S",
                        target),
                new BeautyReceptionInternalApi.OperationRef("O"));
    }

    private static WorkOrderTaskVO task(String number) {
        return new WorkOrderTaskVO(
                number,
                null,
                1,
                "SKU",
                "D",
                BigDecimal.ONE,
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                "COUNT",
                "WAITING",
                0,
                "UNASSIGNED",
                "UNBOUND",
                false,
                null,
                null,
                null,
                List.of(),
                new WorkOrderTaskSnapshotVO(
                        1,
                        "G",
                        "SKU",
                        "D",
                        "a".repeat(64),
                        60,
                        0,
                        0,
                        List.of(),
                        List.of(),
                        List.of()),
                List.of(),
                List.of(),
                List.of("READ"));
    }

    @Test
    void detailsRequireTheSameOrderedTaskIdentitiesAsTheirRoot() {
        var root =
                new WorkOrderVO(
                        "W",
                        "A",
                        1,
                        "OPEN",
                        null,
                        "UNBOUND",
                        "O",
                        "SUCCEEDED",
                        List.of("T", "T2"),
                        OffsetDateTime.parse("2026-10-20T10:00:00Z"));
        var correct = List.of(task("T"), task("T2"));
        var good =
                new WorkOrderDetailVO(
                        root, new CustomerIdentityVO("USER", "U"), 1, correct, List.of());
        assertThat(BeautyReceptionResponses.require(call("GET_WORK_ORDER", "W"), good))
                .isSameAs(good);
        var mine = new MyWorkOrderVO(root, 1, correct, List.of());
        assertThat(BeautyReceptionResponses.require(call("GET_MY_WORK_ORDER", "W"), mine))
                .isSameAs(mine);
        var invalid =
                List.of(
                        List.<WorkOrderTaskVO>of(),
                        List.of(task("T")),
                        List.of(task("T"), task("FOREIGN")),
                        List.of(task("T"), task("T")),
                        List.of(task("T"), task("T2"), task("EXTRA")),
                        List.of(task("T2"), task("T")));
        assertAll(
                invalid.stream()
                        .flatMap(
                                tasks ->
                                        Stream.of(
                                                new WorkOrderDetailVO(
                                                        root,
                                                        new CustomerIdentityVO("USER", "U"),
                                                        1,
                                                        tasks,
                                                        List.of()),
                                                new MyWorkOrderVO(root, 1, tasks, List.of())))
                        .map(
                                detail ->
                                        () ->
                                                assertThatThrownBy(
                                                                () ->
                                                                        BeautyReceptionResponses
                                                                                .require(
                                                                                        call(
                                                                                                "DETAIL",
                                                                                                "W"),
                                                                                        detail))
                                                        .isInstanceOf(
                                                                IllegalArgumentException.class)));
    }

    @Test
    void preservesPersistedClaimRevisionExpiredChallengeAndSchema2Operation() {
        var claim = new GuestClaimVO("O", "G", "U", 7L, "SUCCEEDED", null);
        assertThatCode(() -> BeautyReceptionResponses.require(call("CLAIM_GUEST", "G"), claim))
                .doesNotThrowAnyException();
        assertThat(
                        BeautyReceptionResponses.require(call("CLAIM_GUEST", "G"), claim)
                                .claimRevision())
                .isEqualTo(7);
        var issued = OffsetDateTime.parse("2020-01-01T00:00:00Z");
        var challenge =
                new ArrivalChallengeVO("CH", "S", "k".repeat(43), issued, issued.plusSeconds(90));
        assertThat(
                        BeautyReceptionResponses.require(
                                call("ISSUE_ARRIVAL_CHALLENGE", null), challenge))
                .isSameAs(challenge);
        var source = new OperationVO("O", null, "PENDING", "SUBMIT_SOURCE", "RETRY", "W", null);
        assertThat(BeautyReceptionResponses.require(call("GET_OPERATION", "O"), source))
                .isSameAs(source);
    }

    @Test
    void preservesActualNullableBillAndRejectsMissingSuccessIdentity() {
        var observation =
                new SourceObservationVO(
                        "UNKNOWN",
                        1,
                        null,
                        null,
                        2L,
                        "a".repeat(64),
                        Instant.parse("2020-01-01T00:00:00Z"),
                        "DEPENDENCY_UNAVAILABLE",
                        false);
        var source =
                new SourceBillVO(
                        "CO",
                        "ORDER",
                        "SO",
                        "CNY",
                        null,
                        null,
                        null,
                        null,
                        null,
                        "WHOLE_SOURCE",
                        List.of(),
                        List.of(),
                        observation,
                        false,
                        List.of("UNKNOWN"));
        var bill =
                new WorkOrderBillVO(
                        "W",
                        1,
                        "mark",
                        observation.observedAt(),
                        List.of(source),
                        List.of(),
                        false,
                        List.of("UNKNOWN"));
        assertThatCode(() -> BeautyReceptionResponses.require(call("GET_BILL", "W"), bill))
                .doesNotThrowAnyException();
        assertThat(
                        BeautyReceptionResponses.require(call("GET_BILL", "W"), bill)
                                .sources()
                                .getFirst()
                                .dueMinor())
                .isNull();
        assertThatThrownBy(() -> BeautyReceptionResponses.require(call("GET_BILL", "OTHER"), bill))
                .isInstanceOf(IllegalArgumentException.class);
        var missing = new GuestRegistrationVO(null, null, null, null, null, "SUCCEEDED", null);
        assertThatThrownBy(
                        () ->
                                BeautyReceptionResponses.require(
                                        call("REGISTER_GUEST", null), missing))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
