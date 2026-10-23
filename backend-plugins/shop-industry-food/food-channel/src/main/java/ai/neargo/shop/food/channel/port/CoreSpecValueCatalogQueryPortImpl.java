package ai.neargo.shop.food.channel.port;

import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.spi.product.SpecValueCatalogInternalApi;
import ai.neargo.shop.spi.product.SpecValueCatalogQueryPort;
import ai.neargo.shop.svc.ServiceName;
import ai.neargo.svc.client.CallOutcome;
import ai.neargo.svc.client.ServiceCallException;
import ai.neargo.svc.client.ServiceCalls;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Supplier;

/** Reads original dictionary facts once; transport failures never become an empty set or new authorization. */
public class CoreSpecValueCatalogQueryPortImpl implements SpecValueCatalogQueryPort {
    private final Supplier<SpecValueCatalogInternalApi> api;
    public CoreSpecValueCatalogQueryPortImpl(Supplier<SpecValueCatalogInternalApi> api) { this.api = api; }

    @Override
    public List<SpecValueFact> getValues(String entity, List<String> numbers) {
        SpecValueCatalogQueryPort.validateLookup(entity, numbers);
        try {
            return ServiceCalls.call(ServiceName.PLATFORM, () -> {
                var result = api.get().lookupBatch(new SpecValueCatalogInternalApi.BatchLookup(entity, numbers));
                var seen = new HashSet<String>();
                if (result == null || result.size() > numbers.size() || result.stream().anyMatch(f -> f == null
                        || !numbers.contains(f.valueNo()) || !seen.add(f.valueNo()) || !validKey(f.dimNo(), 64)
                        || f.label() == null || f.label().isBlank() || f.dimName() == null || f.dimName().isBlank()
                        || f.valueType() == null || !Set.of("ENUM", "QUANT").contains(f.valueType())
                        || f.usageType() == null || !Set.of("SALE", "PROP").contains(f.usageType())
                        || f.valueVersion() < 0 || f.dimVersion() < 0
                        || !visible(entity, f.valueScope(), f.valueEntityNo())
                        || !visible(entity, f.dimScope(), f.dimEntityNo()))) {
                    throw new ServiceCallException(CallOutcome.REMOTE_ERROR, ServiceName.PLATFORM, 0,
                            "Invalid original specification metadata response");
                }
                return List.copyOf(result);
            });
        } catch (ServiceCallException failure) {
            ErrorCode code = switch (failure.statusCode()) {
                case 400 -> ErrorCode.BAD_REQUEST;
                case 403 -> ErrorCode.FORBIDDEN;
                case 404 -> ErrorCode.NOT_FOUND;
                case 409 -> ErrorCode.CONFLICT;
                default -> null;
            };
            if (code != null) throw BizException.of(code);
            throw failure;
        }
    }

    private static boolean visible(String entity, String scope, String owner) {
        return "PLATFORM".equals(scope) || "MERCHANT".equals(scope) && entity.equals(owner);
    }

    private static boolean validKey(String value, int max) {
        return value != null && !value.isBlank() && value.length() <= max && value.equals(value.trim());
    }
}
