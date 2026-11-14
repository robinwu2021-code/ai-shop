package ai.neargo.shop.merchant.service.impl;
import ai.neargo.common.data.scope.DataScopeContext;
import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.merchant.dto.StoreProfileVO;
import ai.neargo.shop.merchant.entity.MchStore;
import ai.neargo.shop.merchant.mapper.MerchantMappers.MchStoreMapper;
import ai.neargo.shop.merchant.service.MerchantStoreService;
import ai.neargo.shop.merchant.service.StoreSettingsService;
import ai.neargo.shop.spi.user.StoreOwnershipWritePort;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.Map;
import ai.neargo.shop.merchant.service.PayMerchantMirror;
import java.util.Objects;

@Service
public class StoreSettingsServiceImpl implements StoreSettingsService {
    private final PayMerchantMirror mirror;
    private final MerchantStoreService profiles;
    private final MchStoreMapper stores;
    private final StoreOwnershipWritePort ownership;
    public StoreSettingsServiceImpl(MerchantStoreService profiles,MchStoreMapper stores,StoreOwnershipWritePort ownership,PayMerchantMirror mirror) {
        this.mirror=mirror; this.profiles=profiles; this.stores=stores; this.ownership=ownership;
    }
    @Override public View load(String entity,String store) {
        var p=profiles.profile(entity,store);
        if(p.name()==null) throw BizException.of(ErrorCode.NOT_FOUND);
        return new View(p,Map.of("BASIC",version(p.name(),p.address(),p.addressDetail()),"HOURS",version(p.openHours())));
    }
    private static String version(String... fields) {
        // Length framing distinguishes null, empty and concatenation boundaries.
        StringBuilder value=new StringBuilder();
        for(String field:fields) value.append(field==null ? "-1:" : field.length()+":"+field);
        return StoreContentVersion.hash(value.toString());
    }
    private void requireVersion(String entity,String store,String section,String expected) {
        ownership.lockAndCheck("MAIN",entity,store);
        if(!Objects.equals(load(entity,store).sectionVersions().get(section),expected)) throw BizException.of(ErrorCode.CONFLICT);
    }
    @Override @Transactional
    public View basic(String entity,String store,String expected,String name,String address,String detail) {
        requireVersion(entity,store,"BASIC",expected);
        DataScopeContext.executeWithoutScope(()->stores.update(null,Wrappers.<MchStore>lambdaUpdate()
            .eq(MchStore::getEntityNo,entity).eq(MchStore::getStoreNo,store)
            .set(MchStore::getName,name.trim()).set(MchStore::getAddress,address.trim()).set(MchStore::getAddressDetail,detail.trim())));
        mirror.changed(entity);
        return load(entity,store);
    }
    @Override @Transactional
    public View hours(String entity,String store,String expected,String hours) {
        requireVersion(entity,store,"HOURS",expected);
        DataScopeContext.executeWithoutScope(()->stores.update(null,Wrappers.<MchStore>lambdaUpdate()
            .eq(MchStore::getEntityNo,entity).eq(MchStore::getStoreNo,store).set(MchStore::getOpenHours,hours.trim())));
        return load(entity,store);
    }
}
