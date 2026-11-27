package ai.neargo.shop.platform.port;

import ai.neargo.common.data.scope.DataScopeContext;
import ai.neargo.shop.platform.entity.SysIndustry;
import ai.neargo.shop.platform.mapper.PlatformMappers.IndustryMapper;
import ai.neargo.shop.spi.dict.DictItem;
import ai.neargo.shop.spi.dict.DictType;
import ai.neargo.shop.spi.dict.DictionarySource;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/** 进件行业的来源：旧表 {@code sys_industry}。基础能力库落地后由 {@code sys_dict_item} 来源替换。 */
@Component
public class OnboardIndustryDictionarySource implements DictionarySource {

    private final IndustryMapper industries;

    public OnboardIndustryDictionarySource(IndustryMapper industries) {
        this.industries = industries;
    }

    @Override
    public DictType type() {
        return DictType.ONBOARD_INDUSTRY;
    }

    @Override
    public List<DictItem> items() {
        return DataScopeContext.executeWithoutScope(() -> industries.selectList(Wrappers.<SysIndustry>lambdaQuery()))
                .stream()
                .map(r -> new DictItem(r.getTenantNo(), DictType.ONBOARD_INDUSTRY, r.getIndustry(), null, null,
                        r.getName(), Map.of(DictItem.LANG_ZH_CN, r.getName()), r.getSort() == null ? 0 : r.getSort(),
                        Boolean.TRUE.equals(r.getEnabled()), Map.of()))
                .toList();
    }
}
