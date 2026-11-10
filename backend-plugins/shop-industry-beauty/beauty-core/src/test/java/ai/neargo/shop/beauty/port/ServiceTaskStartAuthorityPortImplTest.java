package ai.neargo.shop.beauty.port;

import static org.mockito.ArgumentMatchers.any;

import static ai.neargo.shop.spi.personnel.PersonnelOperatorFactsPort.LinkState.UNLINKED;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import ai.neargo.shop.auth.DelegatedIdentityContext;
import ai.neargo.shop.beauty.policy.ServiceExecutionAccess;
import ai.neargo.shop.beauty.service.ServiceTaskActionService.TaskActionIntentV2;
import ai.neargo.shop.beauty.service.impl.TaskStartJournal.Frozen;
import ai.neargo.shop.beauty.service.impl.TaskStartOriginalReader;
import ai.neargo.shop.spi.personnel.PersonnelOperatorFactsPort.Observation;
import ai.neargo.shop.spi.resource.ServiceTaskStartAuthorityPort.StartAuthorityLookup;
import ai.neargo.shop.spi.security.DelegatedIdentity;
import ai.neargo.shop.spi.trade.ExecutionReference;
import ai.neargo.shop.spi.trade.ServiceExecutionPort.ExecutionScope;
import ai.neargo.shop.spi.user.MerchantOperatorAccessPort;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;

/**
 * MACHINE query must not impersonate the saved current-HUMAN or ask any owner for a new authority.
 */
class ServiceTaskStartAuthorityPortImplTest {
    @Test
    void machineHistoryHasNoSelfOrAccountResolverSideEffectEvenWhenMissing() {
        var reader = mock(TaskStartOriginalReader.class);
        var access = mock(ServiceExecutionAccess.class);
        var accounts = mock(MerchantOperatorAccessPort.class);
        when(reader.queryStartOriginal(null))
                .thenThrow(new IllegalStateException("missing original"));
        var provider =
                new ServiceTaskStartAuthorityPortImpl(reader, access, accounts, Clock.systemUTC());
        assertThrows(IllegalStateException.class, () -> provider.queryOriginal(null));
        verify(reader).queryStartOriginal(null);
        verifyNoInteractions(access, accounts);
    }

    @Test
    void explicitUnlinkedFactIsPersistedButAnOwnerExceptionIsNotADenial() {
        var reader = mock(TaskStartOriginalReader.class);
        var access = mock(ServiceExecutionAccess.class);
        var accounts = mock(MerchantOperatorAccessPort.class);
        var provider =
                new ServiceTaskStartAuthorityPortImpl(reader, access, accounts, Clock.systemUTC());
        var identity = new DelegatedIdentity("MAIN", "MERCHANT", "MCH", "ACTOR");
        var lookup = mock(StartAuthorityLookup.class);
        var reference = mock(ExecutionReference.class);
        var scope = new ExecutionScope("MAIN", "E", "S");
        when(lookup.reference()).thenReturn(reference);
        when(reference.scope()).thenReturn(scope);
        var frozen = mock(Frozen.class);
        var action = mock(TaskActionIntentV2.class);
        when(reader.frozenOriginal(lookup)).thenReturn(frozen);
        when(frozen.action()).thenReturn(action);
        when(action.actor()).thenReturn(identity);
        var unlinked = new Observation(UNLINKED, Instant.parse("2026-10-30T00:00:00Z"), null);
        var observed =
                new ServiceExecutionAccess.SelfObservation(identity, unlinked, "a".repeat(64));
        when(access.observeSelf(scope)).thenReturn(observed);
        assertThrows(
                RuntimeException.class,
                () ->
                        DelegatedIdentityContext.call(
                                identity, () -> provider.authorizeCurrent(lookup)));
        verify(reader).recordUnlinked(lookup, observed);
        verifyNoInteractions(accounts);
        clearInvocations(reader);
        when(access.observeSelf(scope)).thenThrow(new IllegalStateException("owner unavailable"));
        assertThrows(
                IllegalStateException.class,
                () ->
                        DelegatedIdentityContext.call(
                                identity, () -> provider.authorizeCurrent(lookup)));
        verify(reader, never()).recordUnlinked(any(), any());
    }
}
