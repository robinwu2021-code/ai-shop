package ai.neargo.shop.merchant.port;

import ai.neargo.shop.merchant.service.ResourceAdmissionService;
import ai.neargo.shop.spi.resource.ResourceQualificationPort;
import org.springframework.stereotype.Component;

/** Finite current qualification observation through the existing resource admission owner. */
@Component
public final class ResourceQualificationPortImpl implements ResourceQualificationPort {
    private final ResourceAdmissionService service;
    public ResourceQualificationPortImpl(ResourceAdmissionService service) { this.service = service; }
    @Override public Observation observe(Query query) { return service.observeQualifications(query); }
}
