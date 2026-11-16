package ai.neargo.shop.merchant.service;

import ai.neargo.shop.auth.LoginUser;
import ai.neargo.shop.spi.user.MerchantAdminPort;

public interface StoreOpeningService {
    record Receipt(String requestNo, String entityNo, String storeNo, String status) {}
    Receipt open(LoginUser user, String requestNo, String entityNo, String sourceStoreNo,
                 MerchantAdminPort.QuickStartCommand command);
    Receipt find(LoginUser user, String requestNo);
}
