package ai.neargo.shop.merchant.port;

import ai.neargo.shop.merchant.entity.MchAccount;
import ai.neargo.shop.merchant.mapper.MerchantMappers.MchAccountMapper;
import ai.neargo.shop.spi.user.StaffLoginPhonePort;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.springframework.stereotype.Component;

/** {@link StaffLoginPhonePort} 的薄转发实现。 */
@Component
public class StaffLoginPhonePortImpl implements StaffLoginPhonePort {

    private final MchAccountMapper staffMapper;

    public StaffLoginPhonePortImpl(MchAccountMapper staffMapper) {
        this.staffMapper = staffMapper;
    }

    @Override
    public boolean isStaffLoginPhone(String phone) {
        if (phone == null || phone.isBlank()) {
            return false;
        }
        // 不筛 status：停用的也算「已存在」，理由见接口注释
        return staffMapper.selectCount(Wrappers.<MchAccount>lambdaQuery()
                .eq(MchAccount::getLoginPhone, phone)) > 0;
    }
}
