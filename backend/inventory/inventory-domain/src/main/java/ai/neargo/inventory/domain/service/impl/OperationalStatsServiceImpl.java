package ai.neargo.inventory.domain.service.impl;

import ai.neargo.inventory.domain.config.ConditionalOnInventory;
import ai.neargo.inventory.domain.entity.InvEventLog;
import ai.neargo.inventory.domain.entity.InvEventSubscription;
import ai.neargo.inventory.domain.entity.InvStockCount;
import ai.neargo.inventory.domain.entity.InvStockHold;
import ai.neargo.inventory.domain.mapper.InventoryMappers.EventLogMapper;
import ai.neargo.inventory.domain.mapper.InventoryMappers.EventPendingMapper;
import ai.neargo.inventory.domain.mapper.InventoryMappers.EventSubscriptionMapper;
import ai.neargo.inventory.domain.mapper.InventoryMappers.StockCountMapper;
import ai.neargo.inventory.domain.mapper.InventoryMappers.StockHoldMapper;
import ai.neargo.inventory.domain.service.OperationalStatsService;
import ai.neargo.inventory.domain.support.InvEnums;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDateTime;

@ConditionalOnInventory
@Service
public class OperationalStatsServiceImpl implements OperationalStatsService {

    private final EventPendingMapper pendingMapper;
    private final EventLogMapper logMapper;
    private final EventSubscriptionMapper subscriptionMapper;
    private final StockCountMapper countMapper;
    private final StockHoldMapper holdMapper;

    public OperationalStatsServiceImpl(EventPendingMapper pendingMapper, EventLogMapper logMapper,
                                       EventSubscriptionMapper subscriptionMapper, StockCountMapper countMapper,
                                       StockHoldMapper holdMapper) {
        this.pendingMapper = pendingMapper;
        this.logMapper = logMapper;
        this.subscriptionMapper = subscriptionMapper;
        this.countMapper = countMapper;
        this.holdMapper = holdMapper;
    }

    @Override
    public long pendingEvents() {
        return pendingMapper.selectCount(Wrappers.lambdaQuery());
    }

    @Override
    public long unackedEvents() {
        long total = 0;
        for (InvEventSubscription sub : subscriptionMapper.selectList(Wrappers.lambdaQuery())) {
            total += logMapper.selectCount(Wrappers.<InvEventLog>lambdaQuery()
                    .eq(InvEventLog::getOwnerId, sub.getOwnerId()).gt(InvEventLog::getSeq, sub.getAckedSeq()));
        }
        return total;
    }

    @Override
    public long oldestOpenCountMinutes() {
        InvStockCount oldest = countMapper.selectOne(Wrappers.<InvStockCount>lambdaQuery()
                .in(InvStockCount::getStatus, CountFreezeGuard.OPEN)
                .isNotNull(InvStockCount::getStartedAt)
                .orderByAsc(InvStockCount::getStartedAt).last("LIMIT 1"));
        return oldest == null ? 0 : minutesSince(oldest.getStartedAt());
    }

    @Override
    public long oldestReturnHoldMinutes() {
        InvStockHold oldest = holdMapper.selectOne(Wrappers.<InvStockHold>lambdaQuery()
                .eq(InvStockHold::getReason, InvEnums.HoldReason.RETURN_PENDING)
                .eq(InvStockHold::getStatus, InvEnums.HoldStatus.ACTIVE)
                .orderByAsc(InvStockHold::getId).last("LIMIT 1"));
        return oldest == null ? 0 : minutesSince(oldest.getCreatedAt());
    }

    private static long minutesSince(LocalDateTime at) {
        return at == null ? 0 : Math.max(0, Duration.between(at, LocalDateTime.now()).toMinutes());
    }
}
