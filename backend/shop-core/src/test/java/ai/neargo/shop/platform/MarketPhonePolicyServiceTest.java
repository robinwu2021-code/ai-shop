package ai.neargo.shop.platform;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.spi.pay.MarketPort;
import ai.neargo.shop.platform.impl.MarketPhonePolicyServiceImpl;
import ai.neargo.shop.platform.port.SmsAdmissionPortImpl;
import java.util.*;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class MarketPhonePolicyServiceTest {
    private MarketPhonePolicyService service(String ready) {
        var markets=mock(MarketPort.class);
        var rows=List.of(new MarketPort.MarketBrief("CN","Mainland","CNY",2,"Asia/Shanghai",1,true),
                new MarketPort.MarketBrief("TW","Taiwan","TWD",2,"Asia/Taipei",4.4,true));
        when(markets.all()).thenReturn(rows);
        when(markets.find(anyString())).thenAnswer(x->rows.stream().filter(r->r.market().equals(x.getArgument(0))).findFirst());
        var values=new HashMap<String,String>();var settings=mock(SettingService.class);
        when(settings.get(anyString(),anyString())).thenAnswer(x->values.getOrDefault(x.getArgument(0),x.getArgument(1)));
        doAnswer(x->{values.put(x.getArgument(0),x.getArgument(1));return null;}).when(settings).put(anyString(),anyString(),anyString());
        return new MarketPhonePolicyServiceImpl(markets,settings,JsonMapper.builder().build(),ready,"CN=+86,TW=+886");
    }
    @Test void taiwanRecordsRawOrMissingContactWithoutSmsAdmission() {
        var s=service("CN");assertTrue(s.policy("TW").recordOnly());assertFalse(s.policy("TW").smsVerificationEnabled());
        assertDoesNotThrow(()->s.validateContact("TW","09 1234 5678 / 分机"));
        assertDoesNotThrow(()->s.validateContact("TW",null));
        assertEquals(ErrorCode.SMS_VERIFICATION_DISABLED,assertThrows(BizException.class,()->new SmsAdmissionPortImpl(s).requireSmsEnabled("+886900001111")).errorCode());
    }
    @Test void mainlandRetainsItsFormatAndSmsRules() {
        var s=service("CN");assertFalse(s.policy("CN").recordOnly());
        assertDoesNotThrow(()->s.validateContact("CN","13800138000"));assertThrows(BizException.class,()->s.validateContact("CN","000"));
        assertDoesNotThrow(()->new SmsAdmissionPortImpl(s).requireSmsEnabled("13800138000"));assertDoesNotThrow(()->new SmsAdmissionPortImpl(s).requireSmsEnabled("+8613800138000"));
    }
    @Test void configurationIsRegionalAndCannotEnableAnUnconnectedSmsChannel() {
        var s=service("CN");s.save("TW",false,false,"ops");
        assertFalse(s.policy("TW").recordOnly());assertThrows(BizException.class,()->s.validateContact("TW","待补"));
        assertFalse(s.policy("CN").recordOnly());
        assertThrows(BizException.class,()->s.save("TW",true,true,"ops"));assertFalse(s.policy("TW").smsVerificationEnabled());
        s.save("TW",true,false,"ops");assertDoesNotThrow(()->s.validateContact("TW","待补"));
    }
    @Test void aConnectedRegionStillRequiresAnExplicitSmsEnableAndDisablingRevokesIt() {
        var s=service("CN,TW");assertTrue(s.policy("TW").smsChannelReady());assertFalse(s.policy("TW").smsVerificationEnabled());
        s.save("TW",true,true,"ops");assertDoesNotThrow(()->new SmsAdmissionPortImpl(s).requireSmsEnabled("+886900001111"));
        s.save("TW",true,false,"ops");assertThrows(BizException.class,()->new SmsAdmissionPortImpl(s).requireSmsEnabled("+886900001111"));
    }
    @Test void unknownRegionsAndUnmappedCallingPrefixesDoNotBecomeMainland() {
        var s=service("CN");assertThrows(BizException.class,()->s.policy("XX"));assertThrows(BizException.class,()->new SmsAdmissionPortImpl(s).requireSmsEnabled("+999123456"));
    }
}
