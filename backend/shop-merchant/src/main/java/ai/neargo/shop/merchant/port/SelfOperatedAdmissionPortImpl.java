package ai.neargo.shop.merchant.port;

import ai.neargo.common.data.scope.DataScopeContext;
import ai.neargo.shop.common.PageData;
import ai.neargo.shop.merchant.entity.MchEntity;
import ai.neargo.shop.merchant.mapper.MerchantMappers.MchEntityMapper;
import ai.neargo.shop.spi.user.SelfOperatedAdmissionPort;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import java.time.ZoneId;
import org.springframework.stereotype.Component;

@Component
public class SelfOperatedAdmissionPortImpl implements SelfOperatedAdmissionPort {
    private final MchEntityMapper entities;

    public SelfOperatedAdmissionPortImpl(MchEntityMapper entities) { this.entities = entities; }

    @Override
    public PageData<Entry> search(String market, String status, String keyword, long limit) {
        var query = Wrappers.<MchEntity>lambdaQuery().eq(MchEntity::getSelfOperated, 1)
                .eq(MchEntity::getMarket, market).isNull(MchEntity::getArchivedAt)
                .eq(status != null && !status.isBlank(), MchEntity::getStatus, status)
                .orderByDesc(MchEntity::getCreatedAt).orderByDesc(MchEntity::getEntityNo);
        if (keyword != null && !keyword.isBlank()) {
            query.and(q -> q.like(MchEntity::getName, keyword.trim())
                    .or().like(MchEntity::getOwnerUserNo, keyword.trim()));
        }
        var page = DataScopeContext.executeWithoutScope(() -> entities.selectPage(new Page<>(1, limit), query));
        return PageData.of(page.getRecords().stream().map(e -> new Entry(e.getEntityNo(), e.getName(),
                e.getOwnerUserNo(), e.getStatus(), e.getServiceScope(),
                e.getCreatedAt().atZone(ZoneId.systemDefault()).toInstant().toEpochMilli())).toList(),
                page.getTotal(), 1, limit);
    }
}
