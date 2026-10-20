package ai.neargo.shop.pay.storedvalue;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ai.neargo.shop.pay.mapper.storedvalue.SvAccountMapper;
import ai.neargo.shop.pay.storedvalue.entity.SvAccount;
import ai.neargo.shop.pay.storedvalue.service.impl.StoredValueQueryServiceImpl;
import ai.neargo.shop.spi.entitlement.EntitlementContext;
import ai.neargo.shop.spi.storedvalue.StoredValuePort.BalanceQuery;
import java.lang.reflect.Proxy;
import org.junit.jupiter.api.Test;

class StoredValueStoreAccountIsolationTest {
    @Test void balanceSelectsTheCurrentStoreAccountInsteadOfMerchantTotal() {
        var accounts = (SvAccountMapper) Proxy.newProxyInstance(SvAccountMapper.class.getClassLoader(),
                new Class<?>[]{SvAccountMapper.class}, (proxy, method, args) -> {
                    if (method.getName().equals("hasLegacyAccount")) return false;
                    if (!method.getName().equals("findOwner")) throw new AssertionError(method.getName());
                    assertThat(args).as("account lookup must bind store").hasSize(5);
                    var account = new SvAccount();
                    account.setAccountNo("A-" + args[4]); account.setCurrency("CNY"); account.setStatus("ACTIVE");
                    account.setPrincipalMinor("STORE_A".equals(args[4]) ? 5000L : 10000L);
                    account.setBonusMinor(0L); account.setHeldPrincipalMinor(0L); account.setHeldBonusMinor(0L);
                    return account;
                });
        var service = new StoredValueQueryServiceImpl(accounts, null, null, null, null);
        var context = new EntitlementContext("MAIN", "OPERATOR", "req", "0".repeat(64));
        assertThat(service.balance(context, new BalanceQuery("USER", "MERCHANT", "CNY", "STORE_A"))
                .availableMinor()).isEqualTo(5000L);
        assertThat(service.balance(context, new BalanceQuery("USER", "MERCHANT", "CNY", "STORE_B"))
                .availableMinor()).isEqualTo(10000L);
    }

    @Test void balanceCannotFallBackToAMerchantWideAccountWhenStoreIsMissing() {
        assertThatThrownBy(() -> new BalanceQuery("USER", "MERCHANT", "CNY", null))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
