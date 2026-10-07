package ai.neargo.shop.merchant.port;

import ai.neargo.shop.merchant.service.MerchantStaffService;
import ai.neargo.shop.spi.user.StaffSessionPort;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * {@link StaffSessionPort} 的薄转发实现。
 *
 * <p>直接委托给 {@code MerchantStaffService#issueStaffSession} —— 与
 * {@code /biz/auth/login} 的店员那一支<b>走同一条路</b>（同样只认 ACTIVE、
 * 多主体同样按 {@code is_primary} 取默认），免登录切换因此不会出现
 * 「登录能进、切换进不去」或两边进了不同主体这种对不上的情况。
 */
@Component
public class StaffSessionPortImpl implements StaffSessionPort {

    private final MerchantStaffService staffService;

    public StaffSessionPortImpl(MerchantStaffService staffService) {
        this.staffService = staffService;
    }

    @Override
    public Optional<String> issueStaffSession(String phone) {
        if (phone == null || phone.isBlank()) {
            return Optional.empty();
        }
        return staffService.issueStaffSession(phone);
    }
}
