package ai.neargo.shop.beauty.port;

import ai.neargo.shop.spi.resource.MerchantFactsInternalApi;
import ai.neargo.shop.spi.personnel.PersonnelFactsPort;
import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.svc.ServiceName;
import ai.neargo.svc.client.ServiceCallException;
import ai.neargo.svc.client.ServiceCalls;
import java.util.List;

/** Fetches platform owner facts before an industry transaction; failures never become empty availability. */
public class RemotePersonnelFactsPort implements PersonnelFactsPort {
    private final MerchantFactsInternalApi api;
    public RemotePersonnelFactsPort(MerchantFactsInternalApi api) { this.api = api; }
    @Override
    public List<PersonnelFact> getBatch(Query query) {
        try {
            return ServiceCalls.call(ServiceName.PLATFORM, () -> api.personnel(query));
        } catch (ServiceCallException failure) {
            ErrorCode code = switch (failure.statusCode()) {
                case 400 -> ErrorCode.BAD_REQUEST;
                case 403 -> ErrorCode.FORBIDDEN;
                case 404 -> ErrorCode.NOT_FOUND;
                case 409 -> ErrorCode.CONFLICT;
                default -> null;
            };
            if (code != null) { throw BizException.of(code); }
            throw failure;
        }
    }
}
