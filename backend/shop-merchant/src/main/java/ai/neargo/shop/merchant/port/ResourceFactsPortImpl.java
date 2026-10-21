package ai.neargo.shop.merchant.port;

import ai.neargo.common.data.scope.DataScopeContext;
import ai.neargo.shop.merchant.mapper.MerchantMappers.ResourceMapper;
import ai.neargo.shop.spi.resource.ResourceFactsPort;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import org.springframework.stereotype.Component;

/** One scoped SQL statement reads canonical identity facts, without making capacity or qualification promises. */
@Component
public class ResourceFactsPortImpl implements ResourceFactsPort {
    private final ResourceMapper resources;

    public ResourceFactsPortImpl(ResourceMapper resources) {
        this.resources = resources;
    }

    @Override
    public List<ResourceFact> getBatch(Query query) {
        Objects.requireNonNull(query, "query");
        return DataScopeContext.executeWithoutScope(() -> load(query));
    }

    private List<ResourceFact> load(Query query) {
        var rows = resources.selectEffectiveFacts(query.tenantNo(), query.entityNo(), query.storeNo(),
                query.resourceNos(), LocalDateTime.ofInstant(query.at(), ZoneOffset.UTC));
        if (rows.size() > 200) {
            throw new IllegalStateException("too many effective resource facts");
        }
        var identities = new HashSet<String>();
        List<ResourceFact> result = new ArrayList<>();
        for (var row : rows) {
            if (!identities.add(row.resourceNo())) {
                throw new IllegalStateException("ambiguous effective resource employment");
            }
            result.add(new ResourceFact(row.resourceNo(), Type.valueOf(row.resourceType()), row.storeNo(),
                    row.personnelNo(), row.name(), row.capacity(), row.openOccupied()));
        }
        return List.copyOf(result);
    }
}
