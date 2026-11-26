package ai.neargo.shop.merchant.dictionary;

import ai.neargo.shop.merchant.dictionary.entity.SysAuthCode;
import ai.neargo.shop.merchant.dictionary.entity.SysStoreIndustry;
import java.util.List;

/**
 * 商户域读字典的唯一入口：行业与子行业、类目授权码。
 *
 * <p>字典在新设计里属于基础能力库，和商户不在一个库。商户域只读它、不和它同事务，
 * 所以这里只有查询；维护字典的是 {@code dictionary.impl} 里的两个管理服务。
 */
public interface DictionaryCatalog {

    /** 启用中的类目授权码，按排序。 */
    List<SysAuthCode> enabledAuthCodes();

    /** 全部行业与子行业，按行业码、排序。 */
    List<SysStoreIndustry> storeIndustries();
}
