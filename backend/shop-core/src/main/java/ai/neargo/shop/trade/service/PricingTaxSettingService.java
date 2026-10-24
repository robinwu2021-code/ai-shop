package ai.neargo.shop.trade.service;

import ai.neargo.shop.trade.dto.PricingConfigCommands.Context;
import ai.neargo.shop.trade.dto.PricingTaxSettingCommands.ResetReq;
import ai.neargo.shop.trade.dto.PricingTaxSettingCommands.SaveReq;
import ai.neargo.shop.trade.dto.PricingTaxSettingCommands.SettingVO;
import ai.neargo.shop.trade.dto.PricingTaxSettingCommands.Snapshot;

/** Core-owned merchant tax configuration; HTTP permissions and publication evidence are separate slices. */
public interface PricingTaxSettingService {
    SettingVO getPlatform(Context context);
    SettingVO getMerchant(Context context);
    Snapshot requireEffective(Context context);
    SettingVO savePlatform(Context context, SaveReq request, String key);
    SettingVO saveMerchant(Context context, SaveReq request, String key);
    SettingVO resetMerchant(Context context, ResetReq request, String key);
}
