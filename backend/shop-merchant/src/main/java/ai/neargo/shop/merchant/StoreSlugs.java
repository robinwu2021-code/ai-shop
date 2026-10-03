package ai.neargo.shop.merchant;

import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;

import java.util.Set;
import java.util.regex.Pattern;

/**
 * 门店代码（{@code mch_store.slug}）的格式与保留词 —— <b>这一份是唯一真源</b>
 * （TDD-店铺码与分享 §3.6）。
 *
 * <p>它是对外链接 {@code /s/<代码>} 的一段，所以三条约束都不是洁癖：
 *
 * <ul>
 *   <li><b>小写</b> —— 印在名片上、念给人听的东西不该有大小写歧义；而且
 *       {@code store_code} 是 6 位大写，强制小写让两个值域几乎不重叠，
 *       解析时不必猜是哪一种（顺序仍然固定，见 {@code resolveTarget}）。
 *   <li><b>不含点与斜杠</b> —— 它会被拼进 {@code Location} 头，放开就是开放重定向。
 *       短链那一侧另有一道字符白名单，两道都在。
 *   <li><b>首尾不是连字符</b> —— {@code -abc} 与 {@code abc-} 在链接里看着像断了。
 * </ul>
 *
 * <p><b>端上还有一份</b>：b-app 的设置页要在输入时就给出提示，不能等提交回来才报错。
 * 两份规则必须同口径，改这里要改那里（{@code b-app/src/pages/store-settings}）。
 */
public final class StoreSlugs {

    private StoreSlugs() {
    }

    /** 3–32 位，小写字母/数字/连字符，首尾不是连字符 */
    public static final Pattern PATTERN = Pattern.compile("^[a-z0-9][a-z0-9-]{1,30}[a-z0-9]$");

    /**
     * 保留词。
     *
     * <p><b>现在它们在 {@code /s/} 之下撞不着任何路由</b> —— 那为什么要禁？
     * 因为店主会把代码当成 {@code hxmall.top/<代码>} 用（这是短链最自然的读法），
     * 而顶级短链一旦做起来，{@code /s/download} 这种代码就会和官网的下载页撞。
     * 先禁比以后让人改自己的名片容易。
     */
    public static final Set<String> RESERVED = Set.of(
            "s", "c", "b", "dl", "api", "ops", "ops-web", "admin", "login",
            "download", "static", "assets", "media", "uploads", "internal");

    /**
     * 规范化：去空格、转小写。<b>不做别的</b> —— 把 `_` 换成 `-`、把点去掉这类
     * 「帮他修一修」会让他看到的代码和自己输的不一样，而这一串是要印出去的。
     *
     * @return 规范化后的值；入参为空时返回 null（= 不设代码）
     */
    public static String normalize(String raw) {
        if (raw == null) {
            return null;
        }
        String v = raw.trim().toLowerCase(java.util.Locale.ROOT);
        return v.isEmpty() ? null : v;
    }

    /**
     * 校验规范化后的代码。null 直接放行 —— <b>空是合法的</b>，表示不设代码、
     * 链接回落 {@code store_code}。
     *
     * @throws BizException {@link ErrorCode#STORE_SLUG_INVALID} 格式不合或撞保留词。
     *     <b>不用 BAD_REQUEST</b>：那条的文案是「请求参数有误」，店主看了会去改别的格子
     */
    public static void require(String normalized) {
        if (normalized == null) {
            return;
        }
        if (!PATTERN.matcher(normalized).matches() || RESERVED.contains(normalized)) {
            throw BizException.of(ErrorCode.STORE_SLUG_INVALID);
        }
    }
}
