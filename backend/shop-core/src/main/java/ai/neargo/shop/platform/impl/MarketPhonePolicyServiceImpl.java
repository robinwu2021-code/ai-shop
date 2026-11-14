package ai.neargo.shop.platform.impl;

import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.common.Phones;
import ai.neargo.shop.spi.pay.MarketPort;
import ai.neargo.shop.platform.MarketPhonePolicyService;
import ai.neargo.shop.platform.SettingService;
import java.util.*;
import java.util.stream.Collectors;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

@Service
public class MarketPhonePolicyServiceImpl implements MarketPhonePolicyService {
    private final MarketPort markets;
    private final String defaultMarket;
    private final SettingService settings;
    private final ObjectMapper json;
    private final Set<String> readyMarkets;
    private final Map<String,String> prefixes;

    public MarketPhonePolicyServiceImpl(MarketPort markets, SettingService settings, ObjectMapper json,
            @Value("${shop.sms.channel-ready-markets:CN}") String ready,
            @Value("${shop.sms.calling-prefixes:CN=+86,TW=+886,HK=+852,SG=+65,AE=+971,SA=+966}") String prefixConfig,
            @Value("${shop.merchant.default-market:CN}") String defaultMarket) {
        this.defaultMarket=defaultMarket;
        this.markets=markets; this.settings=settings; this.json=json;
        this.readyMarkets=Arrays.stream(ready.split(",")).map(String::trim).filter(s->!s.isEmpty()).collect(Collectors.toUnmodifiableSet());
        var parsed=new LinkedHashMap<String,String>();
        for (String entry:prefixConfig.split(",")) { var kv=entry.trim().split("=",2); if(kv.length!=2 || !kv[0].matches("[A-Z]{2}") || !kv[1].matches("\\+[0-9]{1,4}")) throw new IllegalArgumentException("Invalid SMS calling prefix configuration"); parsed.put(kv[0],kv[1]); }
        this.prefixes=Map.copyOf(parsed);
    }
    private static String key(String market) { return "platform.market-phone-policy."+market; }
    @Override public Policy instancePolicy() { return policy(defaultMarket); }
    @Override public List<Policy> list() { return markets.all().stream().map(this::loadPolicy).toList(); }
    @Override public Policy policy(String market) {
        return loadPolicy(markets.find(market).orElseThrow(()->BizException.of(ErrorCode.NOT_FOUND)));
    }
    private Policy loadPolicy(MarketPort.MarketBrief row) {
        String market=row.market();
        boolean ready=readyMarkets.contains(market) && prefixes.containsKey(market);
        String defaults="CN".equals(market)?"{\"recordOnly\":false,\"smsVerificationEnabled\":true}":"{\"recordOnly\":true,\"smsVerificationEnabled\":false}";
        Map<String,Boolean> value=json.readValue(settings.get(key(market),defaults),new TypeReference<Map<String,Boolean>>() {});
        return new Policy(market,prefixes.get(market),Boolean.TRUE.equals(value.get("recordOnly")),ready && Boolean.TRUE.equals(value.get("smsVerificationEnabled")),ready,row.enabled());
    }
    @Override @Transactional public Policy save(String market, boolean recordOnly, boolean smsEnabled, String operator) {
        var before=policy(market);
        if(smsEnabled && !before.smsChannelReady()) throw BizException.of(ErrorCode.SMS_VERIFICATION_DISABLED);
        settings.put(key(market),json.writeValueAsString(Map.of("recordOnly",recordOnly,"smsVerificationEnabled",smsEnabled)),operator);
        return new Policy(market,before.callingPrefix(),recordOnly,smsEnabled,before.smsChannelReady(),before.marketEnabled());
    }
    @Override public Policy validateContact(String market,String phone) {
        var row=markets.find(market).orElseThrow(()->BizException.of(ErrorCode.NOT_FOUND));
        if(!row.enabled()) throw BizException.of(ErrorCode.BAD_REQUEST);
        var policy=loadPolicy(row);
        if(!policy.recordOnly() && !Phones.valid(phone,market)) throw BizException.of(ErrorCode.BAD_REQUEST);
        return policy;
    }
    @Override public void requireSmsEnabled(String phone) {
        String canonical=phone!=null && phone.matches(Phones.CN_MOBILE)?"+86"+phone:phone;
        var market=prefixes.entrySet().stream().filter(e->canonical!=null && canonical.startsWith(e.getValue()))
                .max(Comparator.comparingInt(e->e.getValue().length())).map(Map.Entry::getKey)
                .orElseThrow(()->BizException.of(ErrorCode.SMS_VERIFICATION_DISABLED));
        var policy=policy(market);
        if(!policy.marketEnabled() || !policy.smsVerificationEnabled())
            throw BizException.of(ErrorCode.SMS_VERIFICATION_DISABLED);
    }
}
