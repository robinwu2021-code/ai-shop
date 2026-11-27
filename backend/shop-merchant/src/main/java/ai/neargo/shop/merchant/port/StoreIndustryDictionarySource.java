package ai.neargo.shop.merchant.port;

import ai.neargo.common.data.scope.DataScopeContext;
import ai.neargo.shop.merchant.dictionary.DictionaryCatalog;
import ai.neargo.shop.spi.dict.DictItem;
import ai.neargo.shop.spi.dict.DictType;
import ai.neargo.shop.spi.dict.DictionaryPort.IndustryRow;
import ai.neargo.shop.spi.dict.DictionarySource;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * 经营类型与子行业的来源：旧表 {@code sys_store_industry}（V643）。基础能力库落地后由 {@code sys_dict_item} 来源替换。
 *
 * <p>行业本身那一行在旧表里子行业码存空串（唯一键里不用 NULL），这里转成“没有上级”的顶层条目。
 */
@Component
public class StoreIndustryDictionarySource implements DictionarySource {

    private final DictionaryCatalog dictionary;

    public StoreIndustryDictionarySource(DictionaryCatalog dictionary) {
        this.dictionary = dictionary;
    }

    @Override
    public DictType type() {
        return DictType.BUSINESS_TYPE;
    }

    @Override
    public List<DictItem> items() {
        return DataScopeContext.executeWithoutScope(dictionary::storeIndustries)
                .stream()
                .map(r -> new IndustryRow(r.getIndustryCode(),
                        r.getSubIndustryCode() == null || r.getSubIndustryCode().isEmpty() ? null
                                : r.getSubIndustryCode(),
                        r.getBusinessTypeCode(), r.getNameZh(), r.getNameTw(), r.getNameEn(),
                        r.getSort() == null ? 0 : r.getSort(), Integer.valueOf(1).equals(r.getEnabled()))
                        .toItem(r.getTenantNo()))
                .toList();
    }
}
