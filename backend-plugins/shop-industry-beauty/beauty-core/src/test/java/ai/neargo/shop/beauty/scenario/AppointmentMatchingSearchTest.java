package ai.neargo.shop.beauty.scenario;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import ai.neargo.shop.beauty.dto.AppointmentAssignmentReq;
import ai.neargo.shop.beauty.dto.AppointmentItemReq;
import ai.neargo.shop.beauty.dto.PublicAvailabilityQuery;
import ai.neargo.shop.beauty.dto.PublicServiceVO;
import ai.neargo.shop.beauty.dto.PublicStoreVO;
import ai.neargo.shop.beauty.dto.PublishServiceDefinitionVersionReq;
import ai.neargo.shop.beauty.dto.PublishStorePolicyVersionReq;
import ai.neargo.shop.beauty.dto.ServiceSalesVO;
import ai.neargo.shop.beauty.entity.BtyServiceRequirement;
import ai.neargo.shop.beauty.mapper.BeautyMappers;
import ai.neargo.shop.beauty.service.ServiceCatalogQueryService;
import ai.neargo.shop.beauty.service.impl.AppointmentAvailabilityServiceImpl;
import ai.neargo.shop.beauty.service.impl.AppointmentMatching;
import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.spi.personnel.PersonnelFactsPort;
import ai.neargo.shop.spi.product.ServiceCatalogFactsPort;
import ai.neargo.shop.spi.product.StoreSalesCatalogPort;
import ai.neargo.shop.spi.resource.ResourceBookingPort;
import ai.neargo.shop.spi.resource.ResourceCandidatesPort;
import ai.neargo.shop.spi.resource.ResourceFactsPort;
import ai.neargo.shop.spi.resource.ResourcePlanContract;
import ai.neargo.shop.spi.schedule.ScheduleWindowsPort;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Real matching/availability with bounded external fact fixtures, never an owner admission substitute. */
class AppointmentMatchingSearchTest {
    private final OffsetDateTime at = OffsetDateTime.now(ZoneOffset.UTC).plusDays(1)
            .withHour(10).withMinute(0).withSecond(0).withNano(0);
    private final List<AppointmentItemReq> items = List.of(new AppointmentItemReq("SKU", 1));
    private final ResourceBookingPort.Scope scope = new ResourceBookingPort.Scope("MAIN", "E", "S");
    private final AtomicInteger reads = new AtomicInteger();

    @Test
    void flexibleShortRoleIsReassignedSoAutomaticPreviewAndPublicSlotRemainAvailable() {
        var matching = asymmetric();
        var result = matching.match(scope, true, items, List.of(), null, at);
        assertThat(result.complete()).isTrue();
        assertThat(result.snapshot().assignments()).extracting(a -> a.personnelNo()).containsExactly("P2", "P1");
        assertThat(ResourcePlanContract.decode(result.plan()).demands()).hasSize(2);
        // Candidate, personnel, schedule and capacity are read once, independent of search nodes.
        assertThat(reads.get()).isEqualTo(4);
        var search = new AppointmentAvailabilityServiceImpl(matching, null).queryPublicAvailability(
                new PublicAvailabilityQuery("S", items, at, at.plusMinutes(5)));
        assertThat(search.complete()).isTrue();
        assertThat(search.candidates()).hasSize(1);
    }

    @Test
    void partiallySpecifiedLongRoleStaysFixedWhileEarlierAutomaticChoiceBacktracks() {
        var result = asymmetric().match(scope, false, items,
                List.of(new AppointmentAssignmentReq(0, "B", "R1", "P1")), null, at);
        assertThat(result.snapshot().assignments()).extracting(a -> a.resourceNo()).containsExactly("R2", "R1");
    }

    @Test
    void impossibleFixedShortRoleIsConflictAndNotSilentlyReassigned() {
        assertError(asymmetric(), List.of(new AppointmentAssignmentReq(0, "A", "R1", "P1")), ErrorCode.CONFLICT);
    }

    @Test
    void exhaustiveSmallImpossibleSearchReturnsCompleteEmptyAvailability() {
        var matching = fixture(List.of(staff("R1", "P1")), List.of(rule("A", "STAFF", 2, 60)), List.of(60));
        assertError(matching, List.of(), ErrorCode.CONFLICT);
        var result = new AppointmentAvailabilityServiceImpl(matching, null).queryPublicAvailability(
                new PublicAvailabilityQuery("S", items, at, at.plusMinutes(5)));
        assertThat(result.candidates()).isEmpty();
        assertThat(result.complete()).isTrue();
    }

    @Test
    void finiteChoiceBudgetCannotClaimAnExhaustiveConflict() {
        var resources = IntStream.rangeClosed(1, 9).mapToObj(i -> staff("R" + i, "P" + i)).toList();
        var matching = fixture(resources, List.of(rule("A", "STAFF", 10, 60)),
                IntStream.range(0, 9).mapToObj(i -> 60).toList());
        assertError(matching, List.of(), ErrorCode.BEAUTY_SEARCH_INCOMPLETE);
        var result = new AppointmentAvailabilityServiceImpl(matching, null).queryPublicAvailability(
                new PublicAvailabilityQuery("S", items, at, at.plusMinutes(5)));
        assertThat(result.complete()).isFalse();
        assertThat(result.candidates()).isEmpty();
        assertThat(reads.get()).isEqualTo(8);
    }

    @Test
    void largeLegalPhysicalQuantitiesReturnIncompleteBeforeExpandingExcessiveCandidateBuckets() {
        List<ResourceFactsPort.ResourceFact> resources = new ArrayList<>();
        resources.add(staff("STAFF", "P1"));
        for (int i = 0; i < 48; i++) {
            resources.add(new ResourceFactsPort.ResourceFact("ROOM" + i, ResourceFactsPort.Type.ROOM,
                    "S", null, "Room", 200, false));
        }
        var matching = fixture(resources, List.of(rule("A", "STAFF", 1, 60),
                rule("B", "ROOM", 100, 60), rule("C", "ROOM", 100, 60)),
                IntStream.range(0, 49).mapToObj(i -> 60).toList());
        assertError(matching, List.of(), ErrorCode.BEAUTY_SEARCH_INCOMPLETE);
        var result = new AppointmentAvailabilityServiceImpl(matching, null).queryPublicAvailability(
                new PublicAvailabilityQuery("S", items, at, at.plusMinutes(5)));
        assertThat(result.complete()).isFalse();
        assertThat(result.candidates()).isEmpty();
    }

    @Test
    void feasibleResultFromFiftyCandidatesRetainsIncompleteEnumerationFlag() {
        var resources = IntStream.rangeClosed(1, 50).mapToObj(i -> staff("R" + i, "P" + i)).toList();
        var result = fixture(resources, List.of(rule("A", "STAFF", 1, 60)),
                IntStream.range(0, 50).mapToObj(i -> 60).toList()).match(scope, true, items, List.of(), null, at);
        assertThat(result.snapshot().assignments()).hasSize(1);
        assertThat(result.complete()).isFalse();
    }

    @Test
    void aliasesForTheSameStaffCannotCreateCapacityAcrossDifferentRoles() {
        var matching = fixture(List.of(staff("R1", "P1"), staff("R2", "P1")),
                List.of(rule("A", "STAFF", 1, 60), rule("B", "STAFF", 1, 60)), List.of(60, 60));
        assertError(matching, List.of(), ErrorCode.CONFLICT);
    }

    @Test
    void existingOccupationOnAnotherStaffAliasCannotBeEvadedByFixedResource() {
        var matching = fixture(List.of(staff("R1", "P1"), staff("R2", "P1")),
                List.of(rule("A", "STAFF", 1, 60)), List.of(60, 60),
                List.of(new ResourceCandidatesPort.Capacity("R2", at.toInstant(),
                        at.plusMinutes(60).toInstant(), 1, 1)));
        assertError(matching, List.of(new AppointmentAssignmentReq(0, "A", "R1", "P1")), ErrorCode.CONFLICT);
    }

    @Test
    void physicalCapacityCanBeSharedAcrossDemandUnits() {
        var matching = fixture(List.of(new ResourceFactsPort.ResourceFact("ROOM", ResourceFactsPort.Type.ROOM,
                "S", null, "Room", 2, false)), List.of(rule("A", "ROOM", 2, 60)), List.of(60));
        var result = matching.match(scope, false, items, List.of(), null, at);
        assertThat(result.snapshot().assignments()).extracting(a -> a.resourceNo()).containsExactly("ROOM", "ROOM");
    }

    @Test
    void repeatedPhysicalUnitsRetainCapacityAcrossItemsAndRejectExplicitDuplicates() {
        var resource = new ResourceFactsPort.ResourceFact("ROOM", ResourceFactsPort.Type.ROOM,
                "S", null, "Room", 2, false);
        var matching = fixture(List.of(resource), List.of(rule("A", "ROOM", 2, 60)), List.of(120));
        var result = matching.match(scope, false, List.of(new AppointmentItemReq("SKU", 2)), List.of(), null, at);
        assertThat(result.snapshot().items()).hasSize(2);
        assertThat(result.snapshot().assignments()).hasSize(4);
        assertThat(ResourcePlanContract.decode(result.plan()).demands()).hasSize(4);
        assertThat(result.snapshot().items()).allSatisfy(item -> assertThat(result.snapshot().assignments().stream()
                .filter(a -> a.appointmentItemNo().equals(item.appointmentItemNo()))).hasSize(2));
        var assignment = new AppointmentAssignmentReq(0, "A", "ROOM", null);
        assertError(matching, List.of(assignment, assignment), ErrorCode.BAD_REQUEST);
        var occupied = fixture(List.of(resource), List.of(rule("A", "ROOM", 2, 60)), List.of(120),
                List.of(new ResourceCandidatesPort.Capacity("ROOM", at.toInstant(),
                        at.plusHours(2).toInstant(), 2, 1)));
        assertError(occupied, List.of(), ErrorCode.CONFLICT);
    }

    @Test
    void ordinaryExpandedQtyReusesOneExplicitSelection() {
        var resource = new ResourceFactsPort.ResourceFact("ROOM", ResourceFactsPort.Type.ROOM,
                "S", null, "Room", 2, false);
        var matching = fixture(List.of(resource), List.of(rule("A", "ROOM", 2, 60)), List.of(120));
        var result = assertDoesNotThrow(() -> matching.match(scope, false,
                List.of(new AppointmentItemReq("SKU", 2)),
                List.of(new AppointmentAssignmentReq(0, "A", "ROOM", null)), null, at));
        assertThat(result.snapshot().items()).hasSize(2);
        assertThat(result.snapshot().assignments()).hasSize(4);
        assertThat(result.snapshot().assignments()).extracting(a -> a.resourceNo())
                .containsExactly("ROOM", "ROOM", "ROOM", "ROOM");
    }

    private AppointmentMatching asymmetric() {
        // The actual DTO validates this supported single-step, same-item 15/60-minute definition.
        new PublishServiceDefinitionVersionReq("SKU", 0L, new PublishServiceDefinitionVersionReq.Duration(60, 0, 0),
                List.of(new PublishServiceDefinitionVersionReq.Requirement("SHORT", "STAFF", "G", 1, 0, 0, 15,
                        "ANY_QUALIFIED"), new PublishServiceDefinitionVersionReq.Requirement("LONG", "STAFF", "G", 1,
                        0, 0, 60, "ANY_QUALIFIED")));
        return fixture(List.of(staff("R1", "P1"), staff("R2", "P2")),
                List.of(rule("A", "STAFF", 1, 15), rule("B", "STAFF", 1, 60)), List.of(60, 15));
    }

    private void assertError(AppointmentMatching matching, List<AppointmentAssignmentReq> specified, ErrorCode code) {
        assertThatThrownBy(() -> matching.match(scope, false, items, specified, null, at))
                .isInstanceOf(BizException.class).extracting(e -> ((BizException) e).errorCode()).isEqualTo(code);
    }

    private ResourceFactsPort.ResourceFact staff(String resource, String person) {
        return new ResourceFactsPort.ResourceFact(resource, ResourceFactsPort.Type.STAFF,
                null, person, person, 1, false);
    }

    private BtyServiceRequirement rule(String id, String type, int qty, int minutes) {
        var rule = new BtyServiceRequirement();
        rule.setRequirementNo(id); rule.setResourceType(type); rule.setQty(qty); rule.setOffsetMin(0);
        rule.setDurationMin(minutes); rule.setSequenceNo(0); rule.setSkillGoodsNo("STAFF".equals(type) ? "G" : null);
        return rule;
    }

    private void read() {
        assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
        reads.incrementAndGet();
    }

    private AppointmentMatching fixture(List<ResourceFactsPort.ResourceFact> resources,
            List<BtyServiceRequirement> requirements, List<Integer> minutes) {
        return fixture(resources, requirements, minutes, List.of());
    }

    private AppointmentMatching fixture(List<ResourceFactsPort.ResourceFact> resources,
            List<BtyServiceRequirement> requirements, List<Integer> minutes,
            List<ResourceCandidatesPort.Capacity> capacities) {
        var policy = new PublishStorePolicyVersionReq.Policy(7,
                new PublishStorePolicyVersionReq.CancellationRule(0, true), Set.of("PREPAID_SINGLE"));
        var store = new PublicStoreVO("S", "store", null, "ACTIVE", "", "", "P", null, policy, null);
        var product = new PublicServiceVO("S", "SKU", "G", "service", null, 1000, "D",
                new PublishServiceDefinitionVersionReq.Duration(60, 0, 0), List.of(), store,
                new ServiceSalesVO("C", "GROUP", 0, "UTC", StoreSalesCatalogPort.SalesMode.UNRESTRICTED,
                        List.of(), true));
        var catalog = mock(ServiceCatalogQueryService.class);
        when(catalog.getPublicStore("S")).thenReturn(store);
        when(catalog.getPublicService(any(), any())).thenReturn(product);
        var facts = mock(ServiceCatalogFactsPort.class);
        var factStore = new ServiceCatalogFactsPort.Store("E", "S", "store", null, "ACTIVE", "", "", null);
        when(facts.store("S")).thenReturn(factStore);
        when(facts.service("S", "SKU")).thenReturn(new ServiceCatalogFactsPort.Service(factStore, "SKU", "G",
                "service", null, 1000, 60, 1L, 2L));
        ResourceCandidatesPort candidates = new ResourceCandidatesPort() {
            @Override public List<ResourceFactsPort.ResourceFact> candidates(Query q) { read(); return resources; }
            @Override public List<Capacity> capacity(CapacityQuery q) { read(); return capacities; }
        };
        PersonnelFactsPort personnel = q -> {
            read();
            return resources.stream().filter(r -> r.personnelNo() != null).map(r ->
                    new PersonnelFactsPort.PersonnelFact(r.personnelNo(), "A" + r.personnelNo(), "S", "Person", "L",
                            true, Instant.EPOCH, null, List.of("TECH"), List.of(new PersonnelFactsPort.Qualification(
                            "Q" + r.personnelNo(), "S", "G", "SKU", "L", Instant.EPOCH, null)))).toList();
        };
        ScheduleWindowsPort schedules = q -> {
            read();
            return IntStream.range(0, resources.size()).mapToObj(i -> new ScheduleWindowsPort.Window(
                    resources.get(i).resourceNo(), "S", "A", at.toInstant(), at.plusMinutes(minutes.get(i)).toInstant(),
                    List.of("W" + i))).toList();
        };
        var rules = mock(BeautyMappers.ServiceRequirementMapper.class);
        when(rules.selectList(any())).thenReturn(requirements);
        var beans = new DefaultListableBeanFactory();
        beans.registerSingleton("facts", facts); beans.registerSingleton("candidates", candidates);
        beans.registerSingleton("personnel", personnel); beans.registerSingleton("schedules", schedules);
        return new AppointmentMatching(catalog, beans.getBeanProvider(ServiceCatalogFactsPort.class),
                beans.getBeanProvider(ResourceCandidatesPort.class), beans.getBeanProvider(PersonnelFactsPort.class),
                beans.getBeanProvider(ScheduleWindowsPort.class), rules);
    }
}
