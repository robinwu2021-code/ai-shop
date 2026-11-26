package ai.neargo.shop.merchant.port;

import ai.neargo.common.data.scope.DataScopeContext;
import ai.neargo.shop.merchant.dto.ResourceCommandScopeRow;
import ai.neargo.shop.merchant.policy.ResourceOccupationGuard;
import ai.neargo.shop.merchant.mapper.MerchantMappers.ResourceMapper;
import ai.neargo.shop.spi.resource.ResourceFactsPort;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import org.springframework.stereotype.Component;

/** Canonical identity plus current anonymous actual use; neither grants capacity admission or qualification. */
@Component
public class ResourceFactsPortImpl implements ResourceFactsPort {
    private final ResourceMapper resources;
    private final ResourceOccupationGuard occupations;

    public ResourceFactsPortImpl(ResourceMapper resources, ResourceOccupationGuard occupations) {
        this.occupations = occupations;
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
                    row.personnelNo(), row.name(), row.capacity(), row.openOccupied(),
                    occupations.currentUsed(new ResourceCommandScopeRow(row.tenantNo(), row.entityNo(), row.sourceStoreNo()),
                            row.resourceNo(), LocalDateTime.ofInstant(query.at(), ZoneOffset.UTC))));
        }
        return List.copyOf(result);
    }
}
