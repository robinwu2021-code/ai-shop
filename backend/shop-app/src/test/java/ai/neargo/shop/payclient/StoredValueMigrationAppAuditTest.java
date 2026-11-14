package ai.neargo.shop.payclient;

import ai.neargo.shop.pay.client.PayInternalApi;
import ai.neargo.shop.pay.client.PayStoredValueMigrationRequests;
import ai.neargo.shop.pay.storedvalue.migration.StoredValueMigrationExecutionService;
import ai.neargo.shop.pay.storedvalue.migration.StoredValueMigrationManifestService;
import ai.neargo.shop.pay.storedvalue.migration.StoredValueMigrationOperator;
import ai.neargo.shop.payclient.impl.RemoteStoredValueMigrationAppService;
import ai.neargo.shop.payclient.impl.StoredValueMigrationAppServiceImpl;
import ai.neargo.shop.spi.platform.AuditLogPort;
import ai.neargo.shop.spi.storedvalue.StoredValueMigrationPlans.ExecutionCommand;
import ai.neargo.shop.spi.storedvalue.StoredValueMigrationPlans.OperatorEvidence;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

/** App audit records supplement the owner's durable receipts without changing approved command transport. */
class StoredValueMigrationAppAuditTest {
    private static final String HASH = "a".repeat(64);
    private static final OperatorEvidence SOURCES = new OperatorEvidence(HASH, List.of());
    private static final ExecutionCommand RESULT = new ExecutionCommand("CMD", "APPROVAL", "RESUME", "DONE", 1, 2);

    @Test
    void embeddedRecordsOnlySuccessfulApprovedCallsAndPreservesTheCommand() {
        var operator = mock(StoredValueMigrationOperator.class);
        var audit = mock(AuditLogPort.class);
        var service = new StoredValueMigrationAppServiceImpl(mock(StoredValueMigrationManifestService.class),
                mock(StoredValueMigrationExecutionService.class), operator, audit);
        when(operator.sources("OP", "APPROVAL", null)).thenReturn(SOURCES);
        when(operator.execute("resume", "OP", "APPROVAL", null, HASH)).thenReturn(RESULT);
        when(operator.command("OP", "APPROVAL", "CMD", null)).thenReturn(RESULT);
        assertAuditedCalls(service, audit);
        verify(operator).execute("resume", "OP", "APPROVAL", null, HASH);
        reset(audit);
        when(operator.execute("resume", "OP", "APPROVAL", null, HASH)).thenThrow(new IllegalStateException("rejected"));
        assertThatThrownBy(() -> service.execute("resume", "OP", "APPROVAL", null, HASH))
                .isInstanceOf(IllegalStateException.class);
        verifyNoInteractions(audit);
    }

    @Test
    void standaloneRecordsTheSameAuditOnlyAfterTheRemoteOwnerSucceeds() {
        var target = mock(PayInternalApi.class);
        var audit = mock(AuditLogPort.class);
        var service = new RemoteStoredValueMigrationAppService(target, audit);
        when(target.svMigrationSources(any())).thenReturn(SOURCES);
        when(target.svMigrationOperator(any())).thenReturn(RESULT);
        when(target.svMigrationCommand(any())).thenReturn(RESULT);
        assertAuditedCalls(service, audit);
        verify(target).svMigrationOperator(new PayStoredValueMigrationRequests.Operator(
                "resume", "OP", "APPROVAL", null, null, HASH));
        reset(audit);
        when(target.svMigrationOperator(any())).thenThrow(new IllegalArgumentException("rejected"));
        assertThatThrownBy(() -> service.execute("resume", "OP", "APPROVAL", null, HASH))
                .isInstanceOf(RuntimeException.class);
        verifyNoInteractions(audit);
    }

    private static void assertAuditedCalls(StoredValueMigrationAppService service, AuditLogPort audit) {
        assertThat(service.operatorSources("OP", "APPROVAL", null)).isSameAs(SOURCES);
        assertThat(service.execute("resume", "OP", "APPROVAL", null, HASH)).isSameAs(RESULT);
        assertThat(service.command("OP", "APPROVAL", "CMD", null)).isSameAs(RESULT);
        verify(audit).record("SV_MIGRATION_OPERATOR_SOURCES", "APPROVAL", "operator=OP, approval=APPROVAL");
        verify(audit).record("SV_MIGRATION_OPERATOR_EXECUTE", "APPROVAL",
                "operator=OP, action=resume, command=CMD, state=DONE", true);
        verify(audit).record("SV_MIGRATION_OPERATOR_COMMAND", "CMD", "operator=OP, approval=APPROVAL");
        verifyNoMoreInteractions(audit);
    }
}
