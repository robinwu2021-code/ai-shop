package ai.neargo.shop.spi.user;

import java.util.List;
import java.util.Optional;

/**
 * 收款到账核验与投影要的平台事实（门店、主体、账号在平台基础库）。商户域实现、走平台句柄；
 * 网关的收款桥接只调这里，不再自己连平台表（TDD-平台基础库换表 第 1 步）。口径与原 SQL 逐条一致：
 * 门店与主体都未删；按 id 查时连租户一起核。
 */
public interface PaymentCollectionFactsPort {

    /** 门店号 → 门店事实；门店已删或所属主体已删为空 */
    Optional<StoreFact> storeByNo(String storeNo);

    /** 租户 + 门店 id → 门店事实；门店已删、主体已删、租户对不上为空 */
    Optional<StoreFact> storeById(String tenantNo, long storeId);

    /** 主体下在用的 B 端账号（账号号、关联的 C 端用户号，可空） */
    List<Account> activeAccounts(String entityNo);

    record StoreFact(long storeId, String tenantNo, String storeNo, String entityNo, String market) {
    }

    record Account(String mchAccountNo, String userNo) {
    }
}
