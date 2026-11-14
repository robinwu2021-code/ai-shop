package ai.neargo.shop.platform;

import java.util.List;

/** Market-specific contact collection and SMS admission. Contact data is never proof of identity. */
public interface MarketPhonePolicyService extends ai.neargo.shop.spi.platform.MarketContactPolicyPort {
    record Policy(String market, String callingPrefix, boolean recordOnly,
                  boolean smsVerificationEnabled, boolean smsChannelReady, boolean marketEnabled) implements ai.neargo.shop.spi.platform.MarketContactPolicyPort.Policy {}
    Policy instancePolicy();
    List<Policy> list();
    Policy policy(String market);
    Policy save(String market, boolean recordOnly, boolean smsEnabled, String operator);
    Policy validateContact(String market, String phone);
    void requireSmsEnabled(String phone);
}
