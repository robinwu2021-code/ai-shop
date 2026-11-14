package ai.neargo.shop.beauty.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ai.neargo.shop.beauty.dto.WorkOrderQueryReq;
import ai.neargo.shop.beauty.dto.WorkOrderSummaryVO;
import ai.neargo.shop.common.PageData;
import ai.neargo.shop.spi.security.DelegatedIdentity;
import ai.neargo.shop.spi.security.InternalCall;
import ai.neargo.shop.spi.security.InternalRequestBinding;

import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.util.List;

class WorkOrderWorkbenchResponsesTest {
    private InternalCall<WorkOrderQueryReq> call() {
        return new InternalCall<>(
                new InternalRequestBinding(
                        1,
                        "BEAUTY_WORK_ORDER_WORKBENCH",
                        "LIST_WORK_ORDERS",
                        new DelegatedIdentity("MAIN", "MERCHANT", "MCH", "ACTOR"),
                        "E",
                        "S",
                        "S"),
                new WorkOrderQueryReq(null, null, null, null, null, 2, 20));
    }

    private WorkOrderSummaryVO row(String store) {
        return new WorkOrderSummaryVO(
                "E",
                store,
                "W",
                null,
                "OPEN",
                1,
                0,
                OffsetDateTime.parse("2026-11-10T00:00:00Z"),
                null,
                null,
                null);
    }

    @Test
    void exactOwnerPageIsAcceptedButForeignStoreAndFakePaginationFail() {
        var expected = new PageData<>(List.of(row("S")), 21, 2, 20);
        assertThat(BeautyReceptionResponses.require(call(), expected)).isSameAs(expected);
        assertThatThrownBy(
                        () ->
                                BeautyReceptionResponses.require(
                                        call(), new PageData<>(List.of(row("FOREIGN")), 21, 2, 20)))
                .isInstanceOf(RuntimeException.class);
        assertThatThrownBy(
                        () ->
                                BeautyReceptionResponses.require(
                                        call(), new PageData<>(List.of(row("S")), 21, 1, 20)))
                .isInstanceOf(RuntimeException.class);
        assertThatThrownBy(
                        () ->
                                BeautyReceptionResponses.require(
                                        call(),
                                        new PageData<>(List.of(row("S"), row("S")), 21, 2, 20)))
                .isInstanceOf(RuntimeException.class);
    }
}
