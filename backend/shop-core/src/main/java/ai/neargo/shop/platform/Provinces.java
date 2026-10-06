package ai.neargo.shop.platform;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 省级 regionCode ↔ 省名（GB/T 2260 国标两位码，34 个省级单位）。
 *
 * <p>给「限购地区」拦截用（#3/#4①）：下单时把收货地址按省名前缀匹配出省级码，
 * 与商品 {@code restricted_regions} 存的两位码比。
 *
 * <p><b>为什么硬编码、不读 sys_region</b>：① 省级码是国标、固定不变；
 * ② 测试库（H2）<b>不灌 sys_region</b>（它靠 Java 迁移从 CSV 载，不进 schema-test.sql），
 * 读库会在测试里恒空、把这道闸悄悄架空；③ 每单查库也不划算。
 * 这份表与前端 {@code packages/shared/src/utils/region.ts#PROVINCE_NAME_BY_CODE} 同源，
 * 两边都是国标常量，不设对账闸（省级码不像小区会变）。
 */
public final class Provinces {

    private Provinces() {
    }

    /** regionCode（两位）→ 省名。顺序照国标。 */
    public static final Map<String, String> NAME_BY_CODE = buildMap();

    private static Map<String, String> buildMap() {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("11", "北京市"); m.put("12", "天津市"); m.put("13", "河北省"); m.put("14", "山西省");
        m.put("15", "内蒙古自治区");
        m.put("21", "辽宁省"); m.put("22", "吉林省"); m.put("23", "黑龙江省");
        m.put("31", "上海市"); m.put("32", "江苏省"); m.put("33", "浙江省"); m.put("34", "安徽省");
        m.put("35", "福建省"); m.put("36", "江西省"); m.put("37", "山东省");
        m.put("41", "河南省"); m.put("42", "湖北省"); m.put("43", "湖南省"); m.put("44", "广东省");
        m.put("45", "广西壮族自治区"); m.put("46", "海南省");
        m.put("50", "重庆市"); m.put("51", "四川省"); m.put("52", "贵州省"); m.put("53", "云南省");
        m.put("54", "西藏自治区");
        m.put("61", "陕西省"); m.put("62", "甘肃省"); m.put("63", "青海省"); m.put("64", "宁夏回族自治区");
        m.put("65", "新疆维吾尔自治区");
        m.put("71", "台湾省"); m.put("81", "香港特别行政区"); m.put("82", "澳门特别行政区");
        return java.util.Collections.unmodifiableMap(m);
    }

    /**
     * 收货地址 → 省级两位码；认不出返回 {@code null}（调用方据此放行，别误伤认不出地址的正常单）。
     *
     * <p>按省名**前缀**匹配（与运费模板 {@code address.startsWith(省名)} 同口径）。
     * 地址一般以省名开头（「浙江省杭州市…」）；直辖市「北京市…」「上海市…」同样成立。
     */
    public static String provinceCodeOf(String address) {
        if (address == null || address.isBlank()) {
            return null;
        }
        String a = address.trim();
        for (Map.Entry<String, String> e : NAME_BY_CODE.entrySet()) {
            if (a.startsWith(e.getValue())) {
                return e.getKey();
            }
        }
        return null;
    }
}
