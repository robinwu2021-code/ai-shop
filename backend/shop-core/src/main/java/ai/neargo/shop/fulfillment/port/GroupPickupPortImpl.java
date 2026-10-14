package ai.neargo.shop.fulfillment.port;

import ai.neargo.common.data.scope.DataScopeContext;
import ai.neargo.shop.common.BizKey;
import ai.neargo.shop.fulfillment.entity.FulGroupPickup;
import ai.neargo.shop.fulfillment.mapper.FulfillmentMappers.GroupPickupMapper;
import ai.neargo.shop.spi.fulfillment.GroupPickupPort;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

/** {@link GroupPickupPort} 实现。 */
@Component
public class GroupPickupPortImpl implements GroupPickupPort {

    private final GroupPickupMapper mapper;

    public GroupPickupPortImpl(GroupPickupMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    @Transactional
    public String createForGroup(String groupNo, String ownerUserNo, String name,
                                 String address, String timeSlot) {
        // 一团一点：库上有 uk_group 兜底，这里先查是为了幂等 —— 开团接口被重复点击是常态
        FulGroupPickup existing = row(groupNo);
        if (existing != null) {
            return existing.getPickupNo();
        }
        FulGroupPickup p = new FulGroupPickup();
        p.setPickupNo(BizKey.next(BizKey.PICKUP_POINT));
        p.setGroupNo(groupNo);
        p.setUserNo(ownerUserNo);
        p.setName(name);
        p.setAddress(address);
        p.setTimeSlot(timeSlot);
        p.setStatus(FulGroupPickup.ACTIVE);
        DataScopeContext.executeWithoutScope(() -> mapper.insert(p));
        return p.getPickupNo();
    }

    @Override
    public Optional<GroupPickup> findByGroup(String groupNo) {
        return Optional.ofNullable(row(groupNo)).map(p -> new GroupPickup(
                p.getPickupNo(), p.getGroupNo(), p.getUserNo(), p.getName(),
                p.getAddress(), p.getTimeSlot(), p.getReceivedAt()));
    }

    @Override
    @Transactional
    public boolean receive(String groupNo, String operatorUserNo) {
        FulGroupPickup p = row(groupNo);
        if (p == null || !p.getUserNo().equals(operatorUserNo)) {
            return false;
        }
        // 重复签收按成功处理：发起人多点一次不该报错，时间以**第一次**为准 ——
        // 覆盖成后一次会让「货到了多久还没人取」这个判断失真
        if (p.getReceivedAt() == null) {
            p.setReceivedAt(System.currentTimeMillis());
            DataScopeContext.executeWithoutScope(() -> mapper.updateById(p));
        }
        return true;
    }

    private FulGroupPickup row(String groupNo) {
        if (groupNo == null || groupNo.isBlank()) {
            return null;
        }
        return DataScopeContext.executeWithoutScope(() ->
                mapper.selectOne(Wrappers.<FulGroupPickup>lambdaQuery()
                        .eq(FulGroupPickup::getGroupNo, groupNo).last("limit 1")));
    }
}
