package ai.neargo.shop.payclient;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import ai.neargo.shop.auth.BizContext;
import ai.neargo.shop.pay.points.admin.PointsWorkbenchService;
import ai.neargo.shop.pay.points.admin.PointsWorkbenchService.StatementAction;
import ai.neargo.shop.pay.points.admin.PointsWorkbenchService.StatementChange;
import ai.neargo.shop.pay.points.statement.StoreStatementService.*;
import ai.neargo.shop.payclient.impl.BizPointsStatementServiceImpl;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class BizPointsStatementServiceTest {
    private final PointsWorkbenchService kernel = mock(PointsWorkbenchService.class);
    private final BizPointsStatementService service = new BizPointsStatementServiceImpl(kernel);
    @AfterEach void clear() { BizContext.clear(); }
    private void store(String store) {
        BizContext.set(new BizContext("M", Set.of(), Set.of(), Set.of("S1", "S2"), store, false));
    }
    @Test void current_store_and_submitted_revision_are_preserved_without_owner_or_payment_action() {
        store("S1");
        var r = new BizPointsStatementService.Change("REQ", "TYPE", "STATEMENT", 7, "CHECKED");
        service.confirm(r, "STAFF");
        verify(kernel).changeStatement(new StatementChange("REQ", "TYPE", "S1", "STATEMENT", 7,
                StatementAction.CONFIRM, "CHECKED"), "STAFF");
        store("S2");
        service.dispute(r, "STAFF");
        verify(kernel).changeStatement(new StatementChange("REQ", "TYPE", "S2", "STATEMENT", 7,
                StatementAction.DISPUTE, "CHECKED"), "STAFF");
        verifyNoMoreInteractions(kernel);
    }
    @Test void line_query_uses_current_store_and_requested_historical_revision() {
        store("S2");
        service.lines("TYPE", "ST", 5, 123, 20);
        verify(kernel).statementLines(new PointsWorkbenchService.StatementLines("TYPE", "S2", "ST", 5, 123, 20));
        verifyNoMoreInteractions(kernel);
    }
    @Test void missing_store_and_empty_dispute_cannot_reach_kernel() {
        BizContext.set(new BizContext("M", Set.of(), Set.of()));
        assertThatThrownBy(() -> service.statements("TYPE", 0, 20)).hasMessageContaining("FORBIDDEN");
        store("S1");
        assertThatThrownBy(() -> service.dispute(new BizPointsStatementService.Change(
                "REQ", "TYPE", "ST", 0, " "), "STAFF")).isInstanceOf(RuntimeException.class);
        verifyNoInteractions(kernel);
    }
    @Test void list_projects_revisions_without_internal_routes_or_source_positions() {
        store("S1");
        var snapshot = new Snapshot("ST", new Period("TYPE", "S1", 1, 2, "PRIVATE_BANK_ROUTE"),
                List.of("PRIVATE_POSITION"), List.of(new Revision(3,
                    List.of(new Line("PRIVATE_POSITION", 8, 100)), 100, "OPS", "REVISED")), "DRAFT", null);
        when(kernel.statements("TYPE", "S1", 0, 20)).thenReturn(new PointsWorkbenchService.StatementPage(
                List.of(new PointsWorkbenchService.StatementView(1, snapshot, 4)), 1, false));
        var page = service.statements("TYPE", 0, 20);
        assertThat(page.rows()).singleElement().satisfies(row -> {
            assertThat(row.revision()).isEqualTo(3);
            assertThat(row.points()).isEqualTo(100);
            assertThat(row.toString()).doesNotContain("PRIVATE_BANK_ROUTE", "PRIVATE_POSITION");
        });
    }
    @Test void revision_rejection_from_kernel_is_returned_as_business_conflict() {
        store("S1");
        when(kernel.changeStatement(any(), any())).thenThrow(new IllegalArgumentException("POINTS_STATEMENT_REVISION_CONFLICT"));
        assertThatThrownBy(() -> service.confirm(new BizPointsStatementService.Change(
                "REQ", "TYPE", "ST", 1, "CHECKED"), "STAFF"))
                .isInstanceOf(ai.neargo.shop.common.BizException.class);
    }
}
