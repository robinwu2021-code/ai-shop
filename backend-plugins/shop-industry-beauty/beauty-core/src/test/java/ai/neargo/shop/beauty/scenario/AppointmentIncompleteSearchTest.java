package ai.neargo.shop.beauty.scenario;

import java.time.Instant;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import ai.neargo.shop.beauty.dto.AppointmentItemReq;
import ai.neargo.shop.beauty.dto.PreviewMyAppointmentReq;
import ai.neargo.shop.beauty.dto.PublicAvailabilityQuery;
import ai.neargo.shop.beauty.dto.PublicServiceVO;
import ai.neargo.shop.beauty.dto.PublicStoreVO;
import ai.neargo.shop.beauty.dto.PublishServiceDefinitionVersionReq;
import ai.neargo.shop.beauty.dto.PublishStorePolicyVersionReq;
import ai.neargo.shop.beauty.dto.ServiceSalesVO;
import ai.neargo.shop.beauty.dto.WorkOrderRequirementVO;
import ai.neargo.shop.beauty.service.impl.ServiceDefinitions;
import ai.neargo.shop.beauty.mapper.BeautyMappers;
import ai.neargo.shop.beauty.policy.AppointmentAccess;
import ai.neargo.shop.beauty.service.ServiceCatalogQueryService;
import ai.neargo.shop.beauty.service.impl.AppointmentAvailabilityServiceImpl;
import ai.neargo.shop.beauty.service.impl.AppointmentMatching;
import ai.neargo.shop.beauty.service.impl.AppointmentServiceImpl;
import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.spi.personnel.PersonnelFactsPort;
import ai.neargo.shop.spi.product.ServiceCatalogFactsPort;
import ai.neargo.shop.spi.product.StoreSalesCatalogPort;
import ai.neargo.shop.spi.resource.ResourceCandidatesPort;
import ai.neargo.shop.spi.resource.ResourceFactsPort;
import ai.neargo.shop.spi.schedule.ScheduleWindowsPort;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;

/** A truncated candidate set cannot prove capacity conflict; embedded preview and anonymous search retain semantics. */
class AppointmentIncompleteSearchTest {
    @Test void previewMapsBoundedSearchToDedicatedSemanticErrorWhileAvailabilityStaysIncomplete() {
        var catalog = mock(ServiceCatalogQueryService.class);
        var policy = mock(PublishStorePolicyVersionReq.Policy.class);
        when(policy.bookingAheadDays()).thenReturn(7);
        var store = new PublicStoreVO("S", "store", null, "ACTIVE", "", "", "P", null, policy, null);
        when(catalog.getPublicStore("S")).thenReturn(store);
        var product = new PublicServiceVO("S", "SKU", "G", "service", null, 1000, "D",
                new PublishServiceDefinitionVersionReq.Duration(30, 0, 0), List.of(), store,
                new ServiceSalesVO("C", "GROUP", 0, "UTC", StoreSalesCatalogPort.SalesMode.UNRESTRICTED,
                        List.of(), true));
        when(catalog.getPublicService(any(), any())).thenReturn(product);
        var facts = mock(ServiceCatalogFactsPort.class);
        var factStore = new ServiceCatalogFactsPort.Store("E", "S", "store", null, "ACTIVE", "", "", null);
        when(facts.store("S")).thenReturn(factStore);
        when(facts.service("S", "SKU")).thenReturn(new ServiceCatalogFactsPort.Service(factStore, "SKU", "G",
                "service", null, 1000, 30, 1L, 2L));
        var candidates = mock(ResourceCandidatesPort.class);
        when(candidates.candidates(any())).thenReturn(IntStream.range(0, 50).mapToObj(i ->
                new ResourceFactsPort.ResourceFact("R" + i, ResourceFactsPort.Type.STAFF, null, null,
                        "resource", 1, true))
                .toList());
        when(candidates.capacity(any())).thenReturn(List.of());
        var schedules = mock(ScheduleWindowsPort.class); when(schedules.getBatch(any())).thenReturn(List.of());
        var rules = mock(ServiceDefinitions.class);
        var rule = new WorkOrderRequirementVO("REQ", null, "STAFF", null, 1, 0, 0, 30, null);
        when(rules.byVersion(any(), any(), any(), any())).thenReturn(new ServiceDefinitions.Snapshot("G", "S", "SKU", "1", 1, "ACTIVE",
                new PublishServiceDefinitionVersionReq.Duration(30, 0, 0), List.of(), List.of(rule), ServiceDefinitions.STEPS,
                List.of(), "h", Instant.EPOCH));
        var beans = new DefaultListableBeanFactory();
        beans.registerSingleton("facts", facts); beans.registerSingleton("candidates", candidates);
        beans.registerSingleton("schedules", schedules);
        var matching = new AppointmentMatching(catalog, beans.getBeanProvider(ServiceCatalogFactsPort.class),
                beans.getBeanProvider(ResourceCandidatesPort.class), beans.getBeanProvider(
                        PersonnelFactsPort.class),
                beans.getBeanProvider(ScheduleWindowsPort.class), rules);
        var access = mock(AppointmentAccess.class);
        var appointments = new AppointmentServiceImpl(null, access, matching, null, null, null, null);
        var at = OffsetDateTime.now(ZoneOffset.UTC).plusHours(1).withSecond(0).withNano(0);
        at = at.plusMinutes(Math.floorMod(-at.getMinute(), 5));
        var preview = new PreviewMyAppointmentReq("S", List.of(new AppointmentItemReq("SKU", 1)), at, null);
        assertThatThrownBy(() -> appointments.previewMyAppointment("U", preview)).isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).errorCode()).isEqualTo(ErrorCode.BEAUTY_SEARCH_INCOMPLETE);
        var search = new AppointmentAvailabilityServiceImpl(matching, access).queryPublicAvailability(
                new PublicAvailabilityQuery("S", preview.items(), at, at.plusMinutes(5)));
        assertThat(search.complete()).isFalse(); assertThat(search.candidates()).isEmpty();
    }
}
