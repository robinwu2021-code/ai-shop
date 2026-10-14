package ai.neargo.shop.spi.platform;

/**
 * 建品规则（商品①）。product 域 → platform：读平台定的那几条前置约束。
 *
 * <p>与 {@link BannedWordPort} 是同一批规则的两半：那个管「不能出现什么词」，
 * 这个管「必须有什么、多长」。两个都在提审那一刻校验 —— <b>拦在进审核队列之前</b>。
 */
public interface ProductPolicyPort {

    Policy current();

    /**
     * @param requireCover    提审前必须有主图。<b>默认 false</b> —— 见下
     * @param titleMinLength  标题最少几个字，0 = 不限
     * @param titleMaxLength  标题最多几个字，0 = 不限
     *
     * <p><b>默认值一律等于「今天的行为」</b>：这三条一旦生效，命中的存量商品
     * 下次提审全会被拦。默认打开等于在没人预告的情况下让一批商家的提交突然失败，
     * 而他们只会看到一个自己没做错什么的报错。
     */
    record Policy(boolean requireCover, int titleMinLength, int titleMaxLength) {
    }

    /** 存的是一个小 JSON。解析不出就退回「今天的行为」—— 配置坏了不该让全平台提交不了 */
    static Policy parse(String json) {
        if (json == null || json.isBlank()) {
            return new Policy(false, 0, 0);
        }
        return new Policy(
                json.contains("\"requireCover\":true"),
                intOf(json, "titleMinLength"),
                intOf(json, "titleMaxLength"));
    }

    private static int intOf(String json, String key) {
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("\"" + key + "\"\\s*:\\s*(\\d+)").matcher(json);
        return m.find() ? Integer.parseInt(m.group(1)) : 0;
    }
}
