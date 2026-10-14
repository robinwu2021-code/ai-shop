package ai.neargo.shop.marketing.campaign;

import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

import java.util.List;

/**
 * {@code mkt_campaign.goods_nos} 这类 JSON 列的读取。
 *
 * <p><b>为什么不能直接 {@code readValue(raw, List.class)}</b>：同一份数据在两个库里
 * 存出来的形状不一样 ——
 * <pre>
 *   MariaDB : ["G0001"]          ← 正常 JSON 数组
 *   H2      : "[\"G0001\"]"      ← **整个数组被再包了一层字符串**
 * </pre>
 * 直接解析时，H2 那份会被解成一个 String 而不是 List，然后抛异常、被兜底成空列表。
 * 后果是**静默的**：活动照常保存、界面照常显示，只是「参与商品」永远是空 ——
 * 限时特价因此对谁都不生效。
 *
 * <p>这个坑本轮踩过第二次了：第一次是 {@code mch_entity.category_codes}，
 * 当时的解法是把列类型从 JSON 改成 VARCHAR。这里改不了 —— V1 迁移已经落到真实库上，
 * 动它会破坏 Flyway 校验和。所以改成**解析时容忍两种形状**。
 */
public final class CampaignJson {

    private static final TypeReference<List<String>> LIST_OF_STRING = new TypeReference<>() {
    };

    private CampaignJson() {
    }

    /**
     * 读一个字符串数组列。
     *
     * @return 解析不出来时返回空列表 —— 脏数据按「全店参与」处理会**扩大**优惠范围，
     *         宁可少发优惠也不能多发
     */
    public static List<String> readStringList(ObjectMapper json, String raw) {
        if (raw == null || raw.isBlank()) {
            return List.of();
        }
        try {
            return json.readValue(raw, LIST_OF_STRING);
        } catch (RuntimeException first) {
            // 再试一次：H2 把整个数组包成了字符串，先剥掉那一层
            try {
                String unwrapped = json.readValue(raw, String.class);
                return json.readValue(unwrapped, LIST_OF_STRING);
            } catch (RuntimeException second) {
                return List.of();
            }
        }
    }
}
