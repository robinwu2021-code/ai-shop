package ai.neargo.shop.portal.internal;

import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.spi.personnel.PersonnelFactsPort;
import ai.neargo.shop.spi.resource.MerchantFactsInternalApi;
import ai.neargo.shop.spi.resource.ResourceFactsPort;
import ai.neargo.shop.spi.schedule.ScheduleWindowsPort;
import ai.neargo.shop.spi.user.StoreDirectoryPort;
import ai.neargo.shop.svc.InternalHttp;
import ai.neargo.shop.svc.InternalServiceResponses;
import java.util.List;
import java.util.Set;
import java.util.function.Supplier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

/** Machine credentials precede canonical scope validation; platform owners remain the only fact authority. */
@RestController
public class InternalMerchantFactsController {
    private final PersonnelFactsPort people;
    private final ResourceFactsPort resources;
    private final ScheduleWindowsPort schedules;
    private final StoreDirectoryPort stores;
    private final String token;
    public InternalMerchantFactsController(PersonnelFactsPort people, ResourceFactsPort resources,
            ScheduleWindowsPort schedules, StoreDirectoryPort stores,
            @Value("${shop.services.internal-token:}") String token) {
        this.people = people;
        this.resources = resources;
        this.schedules = schedules;
        this.stores = stores;
        this.token = token;
    }
    @PostMapping(MerchantFactsInternalApi.PERSONNEL_PATH)
    public ResponseEntity<?> personnel(@RequestBody PersonnelFactsPort.Query query,
            @RequestHeader(value = InternalHttp.TOKEN_HEADER, required = false) String given) {
        return InternalServiceResponses.invoke(token, given, () -> {
            scope(query.tenantNo(), query.entityNo(), query.storeNo(), query.personnelNos());
            return ownerFacts(() -> people.getBatch(query));
        });
    }
    @PostMapping(MerchantFactsInternalApi.RESOURCE_PATH)
    public ResponseEntity<?> resources(@RequestBody ResourceFactsPort.Query query,
            @RequestHeader(value = InternalHttp.TOKEN_HEADER, required = false) String given) {
        return InternalServiceResponses.invoke(token, given, () -> {
            scope(query.tenantNo(), query.entityNo(), query.storeNo(), query.resourceNos());
            return ownerFacts(() -> resources.getBatch(query));
        });
    }
    @PostMapping(MerchantFactsInternalApi.SCHEDULE_PATH)
    public ResponseEntity<?> schedules(@RequestBody ScheduleWindowsPort.Query query,
            @RequestHeader(value = InternalHttp.TOKEN_HEADER, required = false) String given) {
        return InternalServiceResponses.invoke(token, given, () -> {
            scope(query.tenantNo(), query.entityNo(), query.storeNo(), query.resourceNos());
            return ownerFacts(() -> schedules.getBatch(query));
        });
    }
    private <T> T ownerFacts(Supplier<T> query) {
        try { return query.get(); }
        catch (IllegalStateException conflict) { throw BizException.of(ErrorCode.CONFLICT); }
    }
    private void scope(String tenant, String entity, String store, Set<String> references) {
        if (!"MAIN".equals(tenant)) { throw BizException.of(ErrorCode.BAD_REQUEST); }
        reference(entity);
        reference(store);
        references.forEach(this::reference);
        var card = stores.cards(List.of(store)).get(store);
        if (card == null || !store.equals(card.storeNo()) || !entity.equals(card.entityNo())) {
            throw BizException.of(ErrorCode.FORBIDDEN);
        }
    }
    private void reference(String value) {
        if (value == null || value.isBlank() || value.length() > 32 || !value.equals(value.trim())) {
            throw BizException.of(ErrorCode.BAD_REQUEST);
        }
    }
}
