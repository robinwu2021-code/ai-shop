package ai.neargo.shop.elec.support;

import java.util.Map;

/**
 * 城市 → 省（只给供应商看收货地时用）。
 *
 * <p><b>为什么要降到省</b>：一个料号 + 一个数量 + 一个城市，在元器件这个圈子里
 * 足够把买家缩小到几家厂。给到省则既能判断运费与时效，又认不出人。
 *
 * <p>只收常见的那些；认不出就<b>原样返回</b> —— 返回空会让供应商连运费都判断不了，
 * 而那会让他报一个含运费的高价。
 */
public final class Provinces {

    private static final Map<String, String> BY_CITY = Map.ofEntries(
            Map.entry("深圳", "广东"), Map.entry("广州", "广东"), Map.entry("东莞", "广东"),
            Map.entry("佛山", "广东"), Map.entry("珠海", "广东"), Map.entry("惠州", "广东"),
            Map.entry("中山", "广东"), Map.entry("汕头", "广东"),
            Map.entry("上海", "上海"), Map.entry("北京", "北京"), Map.entry("天津", "天津"),
            Map.entry("重庆", "重庆"), Map.entry("香港", "香港"), Map.entry("澳门", "澳门"),
            Map.entry("苏州", "江苏"), Map.entry("南京", "江苏"), Map.entry("无锡", "江苏"),
            Map.entry("昆山", "江苏"), Map.entry("常州", "江苏"), Map.entry("南通", "江苏"),
            Map.entry("杭州", "浙江"), Map.entry("宁波", "浙江"), Map.entry("温州", "浙江"),
            Map.entry("嘉兴", "浙江"), Map.entry("金华", "浙江"),
            Map.entry("成都", "四川"), Map.entry("绵阳", "四川"),
            Map.entry("武汉", "湖北"), Map.entry("西安", "陕西"), Map.entry("青岛", "山东"),
            Map.entry("济南", "山东"), Map.entry("烟台", "山东"),
            Map.entry("厦门", "福建"), Map.entry("福州", "福建"), Map.entry("泉州", "福建"),
            Map.entry("合肥", "安徽"), Map.entry("长沙", "湖南"), Map.entry("南昌", "江西"),
            Map.entry("郑州", "河南"), Map.entry("沈阳", "辽宁"), Map.entry("大连", "辽宁"),
            Map.entry("哈尔滨", "黑龙江"), Map.entry("长春", "吉林"), Map.entry("石家庄", "河北"),
            Map.entry("太原", "山西"), Map.entry("昆明", "云南"), Map.entry("贵阳", "贵州"),
            Map.entry("南宁", "广西"), Map.entry("兰州", "甘肃"), Map.entry("乌鲁木齐", "新疆"));

    private Provinces() {
    }

    /** @return 认得出给省，认不出原样返回（**不返回空** —— 供应商连运费都判断不了会报高价） */
    public static String of(String city) {
        if (city == null || city.isBlank()) {
            return null;
        }
        String c = city.trim().replace("市", "").replace("省", "");
        String hit = BY_CITY.get(c);
        if (hit != null) {
            return hit;
        }
        for (Map.Entry<String, String> e : BY_CITY.entrySet()) {
            if (c.startsWith(e.getKey())) {
                return e.getValue();
            }
        }
        return c;
    }
}
