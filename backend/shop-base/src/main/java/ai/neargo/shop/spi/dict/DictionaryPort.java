package ai.neargo.shop.spi.dict;

import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * 任意域 → 基础能力：读统一字典（经营类型与子行业、进件行业、支付渠道）。
 *
 * <p>此前每种字典各有一条读路：经营类型挂在 {@code MerchantQueryPort.industries()}，进件行业挂在
 * {@code MasterDataPort.industryKnown}，支付渠道名挂在 {@code PayChannelMasterPort.channelName}。
 * 同一种“查一张码表”的事三处各写一份，换库时要改三处。现在只有这一个端口，换库只换来源（见 {@link DictionarySource}）。
 *
 * <p>只读。字典的维护仍在各自的运营端入口；业务与字典不同库，不和字典同事务。
 */
public interface DictionaryPort {

    /** 该类型全部条目（含停用），按来源给的顺序；没有来源时为空 */
    List<DictItem> items(DictType type);

    /** 按编码取一条（含停用）；同码多租户时取第一条 */
    default Optional<DictItem> item(DictType type, String code) {
        if (code == null) return Optional.empty();
        return items(type).stream().filter(i -> code.equals(i.code())).findFirst();
    }

    /** 该租户下是否登记过这个编码（含停用：停用只影响新选择，存量不动） */
    default boolean known(String tenantNo, DictType type, String code) {
        if (tenantNo == null || code == null) return false;
        return items(type).stream().anyMatch(i -> tenantNo.equals(i.tenantNo()) && code.equals(i.code()));
    }

    /** 展示名；查不到或名字为空时给编码本身 —— 界面上宁可显示 WECHAT 也别显示空 */
    default String nameOf(DictType type, String code) {
        return item(type, code).map(DictItem::name).filter(n -> !n.isBlank()).orElse(code);
    }

    /** 经营类型树的扁平视图（含停用），按行业、排序；只有未配置时为空，不能把全部停用误作未配置 */
    default List<IndustryRow> storeIndustries() {
        return items(DictType.BUSINESS_TYPE).stream().map(IndustryRow::of).toList();
    }

    /**
     * 经营类型树的一行（原 {@code MerchantQueryPort.IndustryRow}，TDD-子行业与能力端验收 B1–B3）。
     *
     * @param subIndustryCode  为空 = 行业本身那一行
     * @param businessTypeCode 对应经营类型：子行业决定建店的经营类型；不影响方案
     */
    record IndustryRow(String industryCode, String subIndustryCode, String businessTypeCode,
                       String nameZh, String nameTw, String nameEn, int sort, boolean enabled) {
        public IndustryRow(String industryCode, String subIndustryCode, String businessTypeCode,
                           String nameZh, String nameTw, String nameEn, int sort) {
            this(industryCode, subIndustryCode, businessTypeCode, nameZh, nameTw, nameEn, sort, true);
        }

        static IndustryRow of(DictItem i) {
            boolean top = i.parentCode() == null;
            String businessType = Objects.requireNonNullElse(i.attr(DictItem.ATTR_BUSINESS_TYPE_CODE), i.code());
            return new IndustryRow(top ? i.code() : i.parentCode(), top ? null : i.code(), businessType,
                    i.name(), i.nameI18n().get(DictItem.LANG_ZH_TW), i.nameI18n().get(DictItem.LANG_EN),
                    i.sort(), i.enabled());
        }

        /** 反向：经营类型树的一行 → 字典条目（来源用） */
        public DictItem toItem(String tenantNo) {
            boolean top = subIndustryCode == null;
            var names = new HashMap<String, String>();
            names.put(DictItem.LANG_ZH_CN, nameZh);
            if (nameTw != null) names.put(DictItem.LANG_ZH_TW, nameTw);
            if (nameEn != null) names.put(DictItem.LANG_EN, nameEn);
            return new DictItem(tenantNo, DictType.BUSINESS_TYPE, top ? industryCode : subIndustryCode,
                    top ? null : industryCode, top ? DictItem.LEVEL_TYPE : DictItem.LEVEL_SUB, nameZh, names, sort,
                    enabled, businessTypeCode == null ? Map.of()
                    : Map.of(DictItem.ATTR_BUSINESS_TYPE_CODE, businessTypeCode));
        }

        /** 繁体（zh-TW / zh-HK / zh-MO / Hant）取繁体名，英文取英文名，其余与缺省都取简体 */
        public String displayName(Locale locale) {
            return toItem(null).displayName(locale);
        }
    }
}
