package ai.neargo.shop.beauty.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.ArgumentMatchers.any;

import ai.neargo.shop.beauty.dto.WorkOrderVO;
import ai.neargo.shop.beauty.entity.BtyReceptionOperation;
import ai.neargo.shop.beauty.entity.BtyServiceTask;
import ai.neargo.shop.beauty.entity.BtyWorkOrder;
import ai.neargo.shop.beauty.entity.BtyWorkOrderContext;
import ai.neargo.shop.beauty.entity.BtyTradeBinding;
import ai.neargo.shop.beauty.entity.BtySourceObservation;
import ai.neargo.shop.beauty.entity.BtySourceAllocationGuard;
import ai.neargo.shop.beauty.mapper.BeautyMappers;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;
import tools.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.util.List;

/** Public projections must provide entity CAS without confusing scope/binding versions. */
class WorkOrderQueryTest {
    final ObjectMapper json = new ObjectMapper();
    final BeautyMappers.WorkOrderContextMapper contexts = mock(BeautyMappers.WorkOrderContextMapper.class);
    final BeautyMappers.ServiceTaskMapper tasks = mock(BeautyMappers.ServiceTaskMapper.class);
    final BeautyMappers.ReceptionOperationMapper operations = mock(BeautyMappers.ReceptionOperationMapper.class);
    final BeautyMappers.ServiceSubmissionMapper submissions = mock(BeautyMappers.ServiceSubmissionMapper.class);
    final BeautyMappers.TradeBindingMapper bindings = mock(BeautyMappers.TradeBindingMapper.class);
    final BeautyMappers.SourceObservationMapper observations = mock(BeautyMappers.SourceObservationMapper.class);
    final BeautyMappers.SourceAllocationGuardMapper guards = mock(BeautyMappers.SourceAllocationGuardMapper.class);
    final WorkOrderQuery query = new WorkOrderQuery(
            new AppointmentPersistence(null, null, null, null, null, null, null, null, null, null,
                    json, mock(PlatformTransactionManager.class)), contexts, tasks,
            mock(BeautyMappers.ServiceStepMapper.class), mock(BeautyMappers.ServiceParticipationMapper.class),
            submissions, operations,
            bindings, observations, guards);

    BtyWorkOrder root(boolean legacy) {
        var root = new BtyWorkOrder();
        root.setEntityNo("E"); root.setStoreNo("S"); root.setWorkOrderNo("W");
        root.setVersion(17L); root.setScopeRevision(3L); root.setStatus("OPEN"); root.setScopeFrozen(false);
        var task = new BtyServiceTask();
        task.setTaskNo("T"); task.setSkuNo("SKU"); task.setVersion(29L); task.setScopeRevision(2L);
        task.setBindingRevision(0L); task.setStatus("PENDING"); task.setQty(BigDecimal.ONE);
        task.setDoneQty(BigDecimal.ZERO); task.setVoidedQty(BigDecimal.ZERO); task.setUom("COUNT");
        task.setExecutionSnapshot(json.writeValueAsString(new WorkOrderCoordinator.TaskExecutionSnapshot(
                1, "UNASSIGNED", null, List.of(), null, null)));
        when(tasks.selectForWorkOrder("E", "S", "W")).thenReturn(List.of(task));
        if (!legacy) {
            var context = new BtyWorkOrderContext(); context.setSourceReceptionOperationNo("OP");
            context.setIdentityKind("GUEST"); context.setIdentityRef("G");
            context.setFrozenScope(json.writeValueAsString(new WorkOrderCoordinator.WorkOrderScopeSnapshot(
                    1, 1, "hash", null, null, null, List.of(), List.of())));
            when(contexts.selectScoped("E", "S", "W")).thenReturn(context);
            var operation = new BtyReceptionOperation(); operation.setOperationNo("OP"); operation.setPhase("OPENED");
            when(operations.selectOpenedForWorkOrder("E", "S", "W", "OP")).thenReturn(operation);
        }
        return root;
    }

    @Test void projectsIndependentCasVersionsAndOnlyImplementedSubmissionAction() {
        var detail = query.detail(root(false));
        var tree = json.readTree(json.writeValueAsString(detail));
        assertThat(tree.at("/workOrder/revision").asLong()).isEqualTo(17);
        assertThat(tree.at("/tasks/0/revision").asLong()).isEqualTo(29);
        assertThat(detail.workOrder().scopeRevision()).isEqualTo(3);
        assertThat(detail.tasks().getFirst().scopeRevision()).isEqualTo(2);
        assertThat(detail.tasks().getFirst().bindingRevision()).isZero();
        assertThat(detail.availableActions()).containsExactly("READ", "SUBMIT_SERVICES");
        assertThat(detail.tasks().getFirst().availableActions()).containsExactly("READ", "SUBMIT_SOURCE");
        assertThat(detail.tasks().getFirst().canStart()).isFalse();
    }

    @Test void frozenAndLegacyOrdersStayReadOnly() {
        var frozen = root(false); frozen.setScopeFrozen(true);
        assertThat(query.detail(frozen).availableActions()).containsExactly("READ");
        assertThat(query.detail(frozen).tasks().getFirst().availableActions()).containsExactly("READ");
        when(contexts.selectScoped("E", "S", "W")).thenReturn(null);
        assertThat(query.detail(frozen).availableActions()).isEmpty();
    }

    @Test void observedManualUnitOffersPreparationOnceThenReadsOriginal() {
        var root = root(false);
        tasks.selectForWorkOrder("E", "S", "W").getFirst().setBindingRevision(1L);
        var binding = new BtyTradeBinding(); binding.setTaskNo("T"); binding.setSourceKind("STORE_ORDER");
        binding.setSourceNo("SO"); binding.setSourceLineRef("L"); binding.setGuardNo("GU");
        binding.setAdmissionKind("MANUAL_OFFLINE"); binding.setCustomerKind("GUEST");
        binding.setGuestNo("G"); binding.setIdentityRevision(1L);
        when(bindings.selectCurrentForWorkOrder("E", "S", "W")).thenReturn(List.of(binding));
        var observation = new BtySourceObservation(); observation.setManualState("READY");
        observation.setAdmissionKind("MANUAL_OFFLINE"); observation.setCustomerKind("GUEST");
        observation.setGuestNo("G"); observation.setIdentityRevision(1L);
        observation.setManualVerifiedLines(json.writeValueAsString(List.of(
                new ServiceSourceObservation.ManualQuantityLine("L", "C", "G", "SKU", 1, 1, "COUNT"))));
        when(observations.selectCanonical("E", "S", "SO")).thenReturn(observation);
        var guard = new BtySourceAllocationGuard(); guard.setAdmissionKind("MANUAL_OFFLINE");
        guard.setSourceNo("SO"); guard.setSourceLineRef("L"); guard.setAllocatedQty(BigDecimal.ONE);
        when(guards.selectOne(any())).thenReturn(guard);
        assertThat(query.detail(root).tasks().getFirst().availableActions())
                .containsExactly("READ", "PREPARE_TASK");
        when(submissions.selectTaskPreparationRefs("E", "S", "W"))
                .thenReturn(List.of(new BeautyMappers.TaskPreparationRef("T", "PT1")));
        var prepared = query.detail(root).tasks().getFirst();
        assertThat(prepared.preparationOperationNo()).isEqualTo("PT1");
        assertThat(prepared.availableActions()).containsExactly("READ");
        assertThat(prepared.canStart()).isFalse();
    }

    @Test void originalPreparationReferenceDoesNotGrantActualStart() {
        var root = root(false);
        when(submissions.selectTaskPreparationRefs("E", "S", "W"))
                .thenReturn(List.of(new BeautyMappers.TaskPreparationRef("T", "PREP1")));
        var task = query.detail(root).tasks().getFirst();
        assertThat(task.preparationOperationNo()).isEqualTo("PREP1");
        assertThat(task.canStart()).isFalse();
        assertThat(task.availableActions()).doesNotContain("START", "COMPLETE", "PREPARE_TASK");
    }

    @Test void oldConstructorsDoNotInventVersions() {
        var old = new WorkOrderVO("W", null, 1, "OPEN", null, "UNBOUND", null, null, List.of(), null);
        assertThat(json.readTree(json.writeValueAsString(old)).has("revision")).isFalse();
    }
}
