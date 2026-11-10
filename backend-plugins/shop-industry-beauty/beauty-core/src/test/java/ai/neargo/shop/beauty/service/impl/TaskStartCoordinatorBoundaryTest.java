package ai.neargo.shop.beauty.service.impl;

import static ai.neargo.shop.beauty.policy.TaskStartSchemaVerifier.Installation.ABSENT;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import ai.neargo.shop.auth.DelegatedIdentityContext;
import ai.neargo.shop.beauty.mapper.BeautyMappers.ServiceSubmissionMapper;
import ai.neargo.shop.beauty.policy.ServiceExecutionAccess;
import ai.neargo.shop.beauty.policy.TaskStartSchemaVerifier;
import ai.neargo.shop.beauty.service.ServiceTaskActionService;
import ai.neargo.shop.beauty.service.ServiceTaskActionService.AcceptedSlotSelection;
import ai.neargo.shop.beauty.service.ServiceTaskActionService.StartActionCommandV2;
import ai.neargo.shop.beauty.service.ServiceTaskActionService.TaskActionContext;
import ai.neargo.shop.beauty.service.ServiceTaskActionService.TaskActionIntentV2;
import ai.neargo.shop.beauty.service.impl.TaskStartJournal.Frozen;
import ai.neargo.shop.beauty.service.impl.TaskStartJournal.Phase;
import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.spi.personnel.PersonnelOperatorFactsPort.LinkState;
import ai.neargo.shop.spi.personnel.PersonnelOperatorFactsPort.Observation;
import ai.neargo.shop.spi.personnel.PersonnelOperatorFactsPort.OperatorPersonnelFact;
import ai.neargo.shop.spi.resource.ResourceExecutionEvidencePort;
import ai.neargo.shop.spi.resource.ResourceOccupationPort;
import ai.neargo.shop.spi.resource.ServiceTaskStartAuthorityPort.StartAuthorityLookup;
import ai.neargo.shop.spi.security.DelegatedIdentity;
import ai.neargo.shop.spi.trade.ServiceExecutionEvidencePort;
import ai.neargo.shop.spi.trade.ServiceExecutionPort.ExecutionBinding;
import ai.neargo.shop.spi.trade.ServiceExecutionPort.ExecutionScope;
import ai.neargo.shop.spi.trade.ServiceExecutionStartPort;

import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

/** SOURCE ONLY / NOT_RUN. Local state rules are not Native/Core integration evidence. */
class TaskStartCoordinatorBoundaryTest {
    @Test
    void acceptedAndUnknownNeverBecomeNoEffect() {
        assertEquals(Phase.DISPATCHING, TaskStartJournal.observedPhase(Phase.DISPATCHING, null));
        assertEquals(
                Phase.ACKED, TaskStartJournal.observedPhase(Phase.ACKED, Phase.ABORT_REQUESTED));
        assertEquals(
                Phase.RESOURCE_APPLIED,
                TaskStartJournal.observedPhase(Phase.RESOURCE_APPLIED, Phase.ABORT_REQUESTED));
        assertEquals(
                Phase.RESOURCE_SEALED,
                TaskStartJournal.observedPhase(Phase.RESOURCE_SEALED, Phase.DISPATCHING));
    }

    @Test
    void lateObservationCannotEraseAppliedOrAcked() {
        assertEquals(
                Phase.ACKED, TaskStartJournal.observedPhase(Phase.ACKED, Phase.OWNER_ACCEPTED));
        assertEquals(
                Phase.RESOURCE_APPLIED,
                TaskStartJournal.observedPhase(Phase.RESOURCE_APPLIED, Phase.DISPATCHING));
        assertThrows(
                RuntimeException.class,
                () ->
                        TaskStartJournal.observedPhase(
                                Phase.RESOURCE_APPLIED, Phase.RESOURCE_SEALED));
    }

    @Test
    void missingCapabilityCannotDispatchAnyHumanOrOwnerMethod() {
        var schema = Mockito.mock(TaskStartSchemaVerifier.class);
        Mockito.when(schema.installation()).thenReturn(ABSENT);
        var nativeOwner = Mockito.mock(ResourceOccupationPort.class);
        var evidence = Mockito.mock(ResourceExecutionEvidencePort.class);
        var core = Mockito.mock(ServiceExecutionStartPort.class);
        var coreEvidence = Mockito.mock(ServiceExecutionEvidencePort.class);
        var actions = Mockito.mock(ServiceTaskActionService.class);
        var reader = Mockito.mock(TaskStartOriginalReader.class);
        var db = Mockito.mock(AppointmentPersistence.class);
        var mapper = Mockito.mock(ServiceSubmissionMapper.class);
        var access = Mockito.mock(ServiceExecutionAccess.class);
        var coordinator =
                new TaskStartCoordinator(
                        db,
                        reader,
                        actions,
                        access,
                        schema,
                        mapper,
                        nativeOwner,
                        evidence,
                        core,
                        coreEvidence,
                        Clock.systemUTC());
        assertEquals(0, coordinator.recoverPendingStarts(100));
        Mockito.verifyNoInteractions(
                nativeOwner, evidence, core, coreEvidence, actions, reader, db, mapper, access);
    }

    @Test
    void gateCommitThenSameActorUnlinkedPersistsOriginalBeforeAnyNativeDispatch() {
        var boundary = new GateCommitBoundary();
        var denied = explicitUnlinked(boundary.identity);
        Mockito.when(boundary.access.requireSame(boundary.scope, boundary.current))
                .thenThrow(denied);

        assertSame(
                denied,
                assertThrows(
                        ServiceExecutionAccess.SelfUnlinked.class, boundary::startAsCurrentActor));

        var order = Mockito.inOrder(boundary.reader, boundary.access);
        order.verify(boundary.access).requireSelf(boundary.scope);
        order.verify(boundary.reader).withExecutionOriginal(Mockito.any(), Mockito.any());
        order.verify(boundary.access).requireSame(boundary.scope, boundary.current);
        order.verify(boundary.reader).recordUnlinked(boundary.lookup, denied.original());
        boundary.verifyNoOwnerDispatch();
    }

    @Test
    void gateCommitThenOrdinaryFailureNeverCreatesCancellation() {
        for (var failure :
                List.of(
                        new IllegalStateException("owner unavailable"),
                        BizException.of(ErrorCode.FORBIDDEN))) {
            var boundary = new GateCommitBoundary();
            Mockito.when(boundary.access.requireSame(boundary.scope, boundary.current))
                    .thenThrow(failure);
            assertSame(
                    failure, assertThrows(RuntimeException.class, boundary::startAsCurrentActor));
            Mockito.verify(boundary.reader, Mockito.never())
                    .recordUnlinked(Mockito.any(), Mockito.any());
            boundary.verifyNoOwnerDispatch();
        }
    }

    @Test
    void gateCommitDoesNotTreatAnotherActorsUnlinkedFactAsItsOwnAbort() {
        var boundary = new GateCommitBoundary();
        var denied = explicitUnlinked(new DelegatedIdentity("MAIN", "MERCHANT", "MCH", "OTHER"));
        Mockito.when(boundary.access.requireSame(boundary.scope, boundary.current))
                .thenThrow(denied);
        assertSame(
                denied,
                assertThrows(
                        ServiceExecutionAccess.SelfUnlinked.class, boundary::startAsCurrentActor));
        Mockito.verify(boundary.reader, Mockito.never())
                .recordUnlinked(Mockito.any(), Mockito.any());
        boundary.verifyNoOwnerDispatch();
    }

    @Test
    void missingCurrentContextCannotTurnAnUnlinkedCarrierIntoAnAbort() {
        var boundary = new GateCommitBoundary();
        var denied = explicitUnlinked(boundary.identity);
        Mockito.when(boundary.access.requireSame(boundary.scope, boundary.current))
                .thenThrow(denied);
        assertThrows(
                RuntimeException.class,
                () -> boundary.coordinator.start(boundary.context, boundary.command));
        Mockito.verify(boundary.reader, Mockito.never())
                .recordUnlinked(Mockito.any(), Mockito.any());
        boundary.verifyNoOwnerDispatch();
    }

    private static ServiceExecutionAccess.SelfUnlinked explicitUnlinked(
            DelegatedIdentity identity) {
        var at = Instant.parse("2026-10-30T00:00:00Z");
        var fact = new Observation(LinkState.UNLINKED, at, null);
        var original = new ServiceExecutionAccess.SelfObservation(identity, fact, "a".repeat(64));
        return assertThrows(
                ServiceExecutionAccess.SelfUnlinked.class, () -> original.requireLinked(at));
    }

    /**
     * Models a committed local reader result only; no Grant/APPLIED/ACK success proof is
     * manufactured.
     */
    private static final class GateCommitBoundary {
        final DelegatedIdentity identity =
                new DelegatedIdentity("MAIN", "MERCHANT", "MCH", "ACTOR");
        final ExecutionScope scope = new ExecutionScope("MAIN", "E", "S");
        final TaskActionContext context = new TaskActionContext(scope, "WORK", "TASK");
        final StartActionCommandV2 command =
                new StartActionCommandV2(
                        1, 3, 1, List.of(new AcceptedSlotSelection("REQ", 0, 1)), "KEY");
        final Instant at = Instant.parse("2026-10-30T00:00:00Z");
        final ServiceExecutionAccess.Current current =
                new ServiceExecutionAccess.Current(
                        identity,
                        new OperatorPersonnelFact(
                                "PERSON", "EMPLOYMENT", 1, 1, 1, at.minusSeconds(60), null),
                        "a".repeat(64));
        final TaskStartOriginalReader reader = Mockito.mock(TaskStartOriginalReader.class);
        final ServiceExecutionAccess access = Mockito.mock(ServiceExecutionAccess.class);
        final ResourceOccupationPort nativeOwner = Mockito.mock(ResourceOccupationPort.class);
        final ResourceExecutionEvidencePort nativeEvidence =
                Mockito.mock(ResourceExecutionEvidencePort.class);
        final ServiceExecutionStartPort core = Mockito.mock(ServiceExecutionStartPort.class);
        final ServiceExecutionEvidencePort coreEvidence =
                Mockito.mock(ServiceExecutionEvidencePort.class);
        final StartAuthorityLookup lookup = Mockito.mock(StartAuthorityLookup.class);
        final TaskStartCoordinator coordinator;

        GateCommitBoundary() {
            var action = Mockito.mock(TaskActionIntentV2.class);
            var frozen = Mockito.mock(Frozen.class);
            var actions = Mockito.mock(ServiceTaskActionService.class);
            Mockito.when(action.originalBinding()).thenReturn(Mockito.mock(ExecutionBinding.class));
            Mockito.when(action.initialPlan())
                    .thenReturn(
                            new ServiceTaskActionService.InitialPlan(
                                    ServiceTaskActionService.PlanKind.DIRECT,
                                    "direct",
                                    1,
                                    "a".repeat(64),
                                    "b".repeat(64)));
            Mockito.when(action.actor()).thenReturn(identity);
            Mockito.when(frozen.action()).thenReturn(action);
            Mockito.when(frozen.lookup()).thenReturn(lookup);
            Mockito.when(actions.prepareStartActionV2(context, command)).thenReturn(action);
            Mockito.when(access.requireSelf(scope)).thenReturn(current);
            Mockito.when(reader.withExecutionOriginal(Mockito.any(), Mockito.any()))
                    .thenReturn(frozen);
            coordinator =
                    new TaskStartCoordinator(
                            Mockito.mock(AppointmentPersistence.class),
                            reader,
                            actions,
                            access,
                            Mockito.mock(TaskStartSchemaVerifier.class),
                            Mockito.mock(ServiceSubmissionMapper.class),
                            nativeOwner,
                            nativeEvidence,
                            core,
                            coreEvidence,
                            Clock.fixed(at, ZoneOffset.UTC));
        }

        void startAsCurrentActor() {
            DelegatedIdentityContext.call(identity, () -> coordinator.start(context, command));
        }

        void verifyNoOwnerDispatch() {
            Mockito.verifyNoInteractions(nativeOwner, nativeEvidence, core, coreEvidence);
        }
    }
}
