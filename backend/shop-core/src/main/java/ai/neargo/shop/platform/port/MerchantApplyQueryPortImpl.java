package ai.neargo.shop.platform.port;

import ai.neargo.shop.common.Masks;
import ai.neargo.shop.platform.entity.MchEntityApply;
import ai.neargo.shop.platform.mapper.PlatformMappers.MerchantApplyMapper;
import ai.neargo.shop.spi.platform.MerchantApplyQueryPort;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.springframework.stereotype.Component;

import java.util.Optional;

@Component
public class MerchantApplyQueryPortImpl implements MerchantApplyQueryPort {

    private final MerchantApplyMapper applyMapper;

    public MerchantApplyQueryPortImpl(MerchantApplyMapper applyMapper) {
        this.applyMapper = applyMapper;
    }

    @Override
    public Optional<ApplyContact> latestOf(String entityNo) {
        if (entityNo == null || entityNo.isBlank()) {
            return Optional.empty();
        }
        MchEntityApply a = ai.neargo.common.data.scope.DataScopeContext.executeWithoutScope(() ->
                applyMapper.selectOne(Wrappers.<MchEntityApply>lambdaQuery()
                        .eq(MchEntityApply::getEntityNo, entityNo)
                        .orderByDesc(MchEntityApply::getId)
                        .last("limit 1")));
        if (a == null) {
            return Optional.empty();
        }
        // 脱敏在 Port 这一层做：调用方即使想泄漏也拿不到完整号码
        return Optional.of(new ApplyContact(a.getContactName(), Masks.phone(a.getContactPhone()),
                Boolean.TRUE.equals(a.getAsPickupPoint()), a.getRejectReason()));
    }
}
