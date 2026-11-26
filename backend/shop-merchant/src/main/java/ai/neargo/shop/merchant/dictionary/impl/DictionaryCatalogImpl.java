package ai.neargo.shop.merchant.dictionary.impl;

import ai.neargo.shop.merchant.dictionary.DictionaryCatalog;
import ai.neargo.shop.merchant.dictionary.entity.SysAuthCode;
import ai.neargo.shop.merchant.dictionary.entity.SysStoreIndustry;
import ai.neargo.shop.merchant.dictionary.mapper.DictionaryMappers.SysAuthCodeMapper;
import ai.neargo.shop.merchant.dictionary.mapper.DictionaryMappers.SysStoreIndustryMapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import java.util.List;
import org.springframework.stereotype.Service;

@Service
public class DictionaryCatalogImpl implements DictionaryCatalog {

    private final SysAuthCodeMapper authCodes;
    private final SysStoreIndustryMapper industries;

    public DictionaryCatalogImpl(SysAuthCodeMapper authCodes, SysStoreIndustryMapper industries) {
        this.authCodes = authCodes;
        this.industries = industries;
    }

    @Override
    public List<SysAuthCode> enabledAuthCodes() {
        return authCodes.selectList(Wrappers.<SysAuthCode>lambdaQuery()
                .eq(SysAuthCode::getEnabled, true).orderByAsc(SysAuthCode::getSort));
    }

    @Override
    public List<SysStoreIndustry> storeIndustries() {
        return industries.selectList(Wrappers.<SysStoreIndustry>lambdaQuery()
                .orderByAsc(SysStoreIndustry::getIndustryCode).orderByAsc(SysStoreIndustry::getSort));
    }
}
