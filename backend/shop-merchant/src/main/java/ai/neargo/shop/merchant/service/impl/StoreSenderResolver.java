package ai.neargo.shop.merchant.service.impl;

import ai.neargo.common.data.scope.DataScopeContext;
import ai.neargo.shop.merchant.entity.MchAccount;
import ai.neargo.shop.merchant.entity.MchStore;
import ai.neargo.shop.merchant.mapper.MerchantMappers.MchAccountMapper;
import ai.neargo.shop.spi.platform.MasterDataPort;
import ai.neargo.shop.spi.user.MerchantQueryPort.StoreSender;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.springframework.stereotype.Component;

/**
 * 寄件人口径的唯一一处（TDD-快递100商家寄件 §7 AC12）。
 *
 * <p>两个读者：快递代下单要「实际用哪个」（{@link #effective}），发货设置页要「不填会用哪个」
 * （{@link #defaults}，当 placeholder）。两处各拼一次的话，页面上显示的和快递单上印的迟早不一样。
 */
@Component
public class StoreSenderResolver {

    private final MchAccountMapper staffMapper;
    private final MasterDataPort masterDataPort;

    public StoreSenderResolver(MchAccountMapper staffMapper, MasterDataPort masterDataPort) {
        this.staffMapper = staffMapper;
        this.masterDataPort = masterDataPort;
    }

    /** 发货设置优先，没填的项回落默认 */
    public StoreSender effective(MchStore store) {
        StoreSender d = defaults(store);
        return new StoreSender(
                pick(store.getShipSenderName(), d.name()),
                pick(store.getShipSenderPhone(), d.mobile()),
                pick(store.getShipAddress(), d.address()));
    }

    /**
     * 不看发货设置时的寄件人：门店名、店主登录手机、门店地址（缺省份时用区划路径补）。
     * 门店表上没有单独的电话列，而快递员上门前要打的就是老板。
     */
    public StoreSender defaults(MchStore store) {
        MchAccount owner = DataScopeContext.executeWithoutScope(() ->
                staffMapper.selectOne(Wrappers.<MchAccount>lambdaQuery()
                        .eq(MchAccount::getEntityNo, store.getEntityNo())
                        .eq(MchAccount::getIsOwner, true)
                        .last("limit 1")));
        String mobile = owner == null || owner.getLoginPhone() == null ? "" : owner.getLoginPhone().trim();
        return new StoreSender(store.getName(), mobile, address(store));
    }

    /**
     * 选点地址 + 门牌，缺省份时用区划路径补在前面。
     *
     * <p>选点来的地址常常只到路名（「盐湖区解放路 1 号」）；快递公司按省市分拣，
     * 缺了会拒单或分错网点。区划码是选点时一起存下的，补得上。
     */
    private String address(MchStore store) {
        String base = (store.getAddress() == null ? "" : store.getAddress().trim())
                + (store.getAddressDetail() == null ? "" : store.getAddressDetail().trim());
        if (base.isEmpty() || store.getAdcode() == null || store.getAdcode().isBlank()) {
            return base;
        }
        String path = masterDataPort.regionPathName(store.getAdcode());
        if (path == null || path.equals(store.getAdcode())) {
            return base;
        }
        StringBuilder prefix = new StringBuilder();
        for (String part : path.split("\\s*/\\s*")) {
            if (!part.isBlank() && !base.contains(part)) {
                prefix.append(part);
            }
        }
        return prefix + base;
    }

    private static String pick(String set, String fallback) {
        return set == null || set.isBlank() ? fallback : set.trim();
    }
}
