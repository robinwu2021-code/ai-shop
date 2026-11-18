package ai.neargo.shop.merchantbridge.port;

import ai.neargo.shop.merchant.service.MerchantStaffService;
import ai.neargo.shop.merchantbridge.MerchantDeregisterChecks;
import ai.neargo.shop.spi.merchant.MerchantDeregisterPort;
import org.springframework.stereotype.Component;

/** Merchant rules shared by the App and mini-program deregistration entries (D7). */
@Component
public class MerchantDeregisterPortImpl implements MerchantDeregisterPort {

    private final MerchantDeregisterChecks checks;
    private final MerchantStaffService staff;

    public MerchantDeregisterPortImpl(MerchantDeregisterChecks checks, MerchantStaffService staff) {
        this.checks = checks;
        this.staff = staff;
    }

    @Override
    public boolean blocked(String userNo) {
        return checks.blockers(userNo).stream()
                .anyMatch(b -> !MerchantDeregisterChecks.BUYER_OPEN_ORDERS.equals(b.code()));
    }

    @Override
    public void onDeregistered(String userNo, String phone) {
        staff.retireAccount(userNo, phone);
    }
}
