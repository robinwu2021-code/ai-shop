package ai.neargo.shop.spi.dict;

import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 字典条目，形状对齐基础能力库 {@code sys_dict_item}：换库时调用方拿到的对象不变。
 *
 * @param parentCode 上级条目编码；顶层为空
 * @param level      层级：经营类型为 {@code TYPE} / {@code SUB}，其余类型为空
 * @param name       默认展示名（简体）
 * @param nameI18n   多语言名，键为 {@code zh-CN} / {@code zh-TW} / {@code en}；缺的语言回落 {@link #name}
 * @param attrs      按类型而异的属性，键名见各类型的常量
 */
public record DictItem(String tenantNo, DictType type, String code, String parentCode, String level,
                       String name, Map<String, String> nameI18n, int sort, boolean enabled,
                       Map<String, Object> attrs) {

    public static final String LEVEL_TYPE = "TYPE";
    public static final String LEVEL_SUB = "SUB";
    public static final String LANG_ZH_CN = "zh-CN";
    public static final String LANG_ZH_TW = "zh-TW";
    public static final String LANG_EN = "en";
    /** {@link DictType#BUSINESS_TYPE} 的子行业条目：它决定的经营类型 */
    public static final String ATTR_BUSINESS_TYPE_CODE = "business_type_code";
    /** {@link DictType#ONBOARD_INDUSTRY}：任一通道允许小微进件（"true" / "false"） */
    public static final String ATTR_MICRO_ALLOWED = "micro_allowed";

    public DictItem {
        nameI18n = nameI18n == null ? Map.of() : Map.copyOf(nameI18n);
        attrs = attrs == null ? Map.of() : Map.copyOf(attrs);
    }

    /** 繁体（zh-TW / zh-HK / zh-MO / Hant）取繁体名，英文取英文名，其余与缺省都取简体 */
    public String displayName(Locale locale) {
        if (locale != null && "zh".equals(locale.getLanguage())
                && ("Hant".equals(locale.getScript()) || Set.of("TW", "HK", "MO").contains(locale.getCountry()))) {
            return orName(nameI18n.get(LANG_ZH_TW));
        }
        if (locale != null && "en".equals(locale.getLanguage())) {
            return orName(nameI18n.get(LANG_EN));
        }
        return orName(nameI18n.get(LANG_ZH_CN));
    }

    public String attr(String key) {
        Object v = attrs.get(key);
        return v == null ? null : v.toString();
    }

    private String orName(String localized) {
        return localized == null || localized.isBlank() ? name : localized;
    }
}
