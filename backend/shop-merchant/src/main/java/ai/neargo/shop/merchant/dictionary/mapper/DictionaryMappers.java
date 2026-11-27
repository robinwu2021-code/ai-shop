package ai.neargo.shop.merchant.dictionary.mapper;

import ai.neargo.shop.merchant.dictionary.entity.SysAuthCode;
import ai.neargo.shop.merchant.dictionary.entity.SysStoreIndustry;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;

/**
 * 行业字典与类目授权码的 Mapper。
 *
 * <p>这两张表不是商户的数据，是全平台的字典；新的库表设计把它们并入基础能力库的统一字典。
 * 所以从商户域的 Mapper 集合里拆出来，放在自己的包里：商户域的代码不直接用这里的 Mapper，
 * 只通过 {@link ai.neargo.shop.merchant.dictionary.DictionaryCatalog} 读（守卫：MerchantSubdomainBoundaryTest）。
 */
public final class DictionaryMappers {

    private DictionaryMappers() {
    }

    /** 行业与子行业（V643）。 */
    public interface SysStoreIndustryMapper extends BaseMapper<SysStoreIndustry> {
    }

    /** 类目授权码目录。 */
    public interface SysAuthCodeMapper extends BaseMapper<SysAuthCode> {
    }
}
