package ai.neargo.shop.merchant.service.impl;

import ai.neargo.common.data.scope.DataScopeContext;
import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.common.ExpressCompanies;
import ai.neargo.shop.merchant.entity.MchStore;
import ai.neargo.shop.merchant.mapper.MerchantMappers.MchStoreMapper;
import ai.neargo.shop.merchant.service.StoreShipSettingService;
import ai.neargo.shop.spi.user.MerchantQueryPort;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.regex.Pattern;

@Service
public class StoreShipSettingServiceImpl implements StoreShipSettingService {

    /** 快递公司下单只收手机或座机：数字、短横，7–20 位 */
    private static final Pattern PHONE = Pattern.compile("[0-9-]{7,20}");
    static final int MIN_WEIGHT_G = 100;
    static final int MAX_WEIGHT_G = 30_000;

    private final MchStoreMapper storeMapper;
    private final StoreSenderResolver senderResolver;

    public StoreShipSettingServiceImpl(MchStoreMapper storeMapper, StoreSenderResolver senderResolver) {
        this.storeMapper = storeMapper;
        this.senderResolver = senderResolver;
    }

    @Override
    public ShipSettingVO get(String entityNo, String storeNo) {
        MchStore s = owned(entityNo, storeNo);
        // 回落值与快递单上实际会印的同一处口径（StoreSenderResolver），不在这里再拼一遍
        MerchantQueryPort.StoreSender def = senderResolver.defaults(s);
        return new ShipSettingVO(s.getStoreNo(), s.getShipSenderName(), s.getShipSenderPhone(), s.getShipAddress(),
                s.getShipCarrier(), s.getShipWeightG(), def.name(), def.mobile(), def.address());
    }

    @Override
    @Transactional
    public ShipSettingVO save(String entityNo, String storeNo, ShipSettingCmd cmd) {
        MchStore s = owned(entityNo, storeNo);
        String name = blankToNull(cmd.senderName());
        String phone = blankToNull(cmd.senderPhone());
        String address = blankToNull(cmd.address());
        String carrier = blankToNull(cmd.carrier());
        Integer weight = cmd.weightG();
        if ((name != null && name.length() > 64) || (address != null && address.length() > 255)
                || (phone != null && !PHONE.matcher(phone).matches())
                || (carrier != null && !ExpressCompanies.isValid(carrier))
                || (weight != null && (weight < MIN_WEIGHT_G || weight > MAX_WEIGHT_G))) {
            throw BizException.of(ErrorCode.BAD_REQUEST);
        }
        s.setShipSenderName(name);
        s.setShipSenderPhone(phone);
        s.setShipAddress(address);
        s.setShipCarrier(carrier);
        s.setShipWeightG(weight);
        DataScopeContext.executeWithoutScope(() -> storeMapper.updateById(s));
        return get(entityNo, storeNo);
    }

    private MchStore owned(String entityNo, String storeNo) {
        if (storeNo == null || storeNo.isBlank()) {
            throw BizException.of(ErrorCode.NOT_FOUND);
        }
        MchStore row = DataScopeContext.executeWithoutScope(() -> storeMapper.selectOne(
                Wrappers.<MchStore>lambdaQuery()
                        .eq(MchStore::getEntityNo, entityNo)
                        .eq(MchStore::getStoreNo, storeNo)
                        .last("LIMIT 1")));
        if (row == null) {
            throw BizException.of(ErrorCode.NOT_FOUND);
        }
        return row;
    }

    private static String blankToNull(String v) {
        return v == null || v.isBlank() ? null : v.trim();
    }
}
