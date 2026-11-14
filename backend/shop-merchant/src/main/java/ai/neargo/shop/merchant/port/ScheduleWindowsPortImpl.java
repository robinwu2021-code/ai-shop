package ai.neargo.shop.merchant.port;

import ai.neargo.common.data.scope.DataScopeContext;
import ai.neargo.shop.merchant.mapper.MerchantMappers.ScheduleRuleMapper;
import ai.neargo.shop.merchant.policy.ScheduleWindowAssembler;
import ai.neargo.shop.spi.schedule.ScheduleWindowsPort;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Objects;
import org.springframework.stereotype.Component;

/** One owner SQL snapshot includes related stores before filtering output, so cross-store conflicts cannot hide. */
@Component
public class ScheduleWindowsPortImpl implements ScheduleWindowsPort {
    private final ScheduleRuleMapper rules;

    public ScheduleWindowsPortImpl(ScheduleRuleMapper rules) {
        this.rules = rules;
    }

    @Override
    public List<Window> getBatch(Query query) {
        Objects.requireNonNull(query, "query");
        return DataScopeContext.executeWithoutScope(() -> {
            var from = LocalDateTime.ofInstant(query.startAt(), ZoneOffset.UTC);
            var until = LocalDateTime.ofInstant(query.endAt(), ZoneOffset.UTC);
            var rows = rules.selectWindowRules(query.tenantNo(), query.entityNo(), query.storeNo(),
                    query.resourceNos(), from, until, from.toLocalDate().minusDays(2),
                    until.toLocalDate().plusDays(2));
            if (rows.size() > 2000) {
                throw new IllegalStateException("too many schedule employment rows");
            }
            return ScheduleWindowAssembler.expand(rows, query);
        });
    }
}
