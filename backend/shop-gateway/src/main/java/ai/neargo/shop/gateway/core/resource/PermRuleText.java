package ai.neargo.shop.gateway.core.resource;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 库里的权限式文字 ↔ {@link PermRule}（TDD-管理范围权限搬到网关-权限码方案「规则在库里」）。
 *
 * <p>写法就是 {@link PermRule#toString()} 输出的样子，所以从入口注解抄进库的、和人在库里配的，是同一种文字：
 * <pre>
 *   biz:finance
 *   (pay:sv:plan:read 或 pay:sv:plan:manage)
 *   (biz:customer 且 (pay:sv:recharge:create 或 pay:sv:account:read))
 *   sv.brand.activity.read @BRAND(brandNo)        带范围的码：资源号取自请求上的 brandNo
 *   sv.settlement.read @ENTITY(任一)              带范围的码：空间内对任一主体持有即可
 * </pre>
 * 读不懂的文字返回空 —— 调用方按「留给入口判」处理，不猜。
 */
public final class PermRuleText {

    private static final Pattern TOKEN = Pattern.compile("\\s*(\\(|\\)|且|或|[^\\s()且或@]+(?:\\s*@[A-Z_]+\\([^()\\s]+\\))?)");
    private static final Pattern LEAF = Pattern.compile("^([^\\s()且或@]+)(?:\\s*@([A-Z_]+)\\(([^()\\s]+)\\))?$");
    /** 「任一资源」在文字里的写法 */
    static final String ANY = "任一";

    private PermRuleText() {
    }

    public static Optional<PermRule> parse(String text) {
        if (text == null || text.isBlank()) {
            return Optional.empty();
        }
        List<String> tokens = new ArrayList<>();
        Matcher matcher = TOKEN.matcher(text.trim());
        int end = 0;
        while (matcher.lookingAt()) {
            tokens.add(matcher.group(1).trim());
            end = matcher.end();
            matcher.region(end, text.trim().length());
        }
        if (end != text.trim().length() || tokens.isEmpty()) {
            return Optional.empty();
        }
        int[] position = {0};
        PermRule rule = expression(tokens, position);
        return rule == null || position[0] != tokens.size() ? Optional.empty() : Optional.of(rule);
    }

    /** 一个叶子，或一对括号里用同一种连接词连起来的若干项。括号里混用「且」「或」不认：那样读起来有歧义。 */
    private static PermRule expression(List<String> tokens, int[] position) {
        if (position[0] >= tokens.size()) {
            return null;
        }
        String token = tokens.get(position[0]++);
        if (!token.equals("(")) {
            return leaf(token);
        }
        List<PermRule> children = new ArrayList<>();
        String joiner = null;
        while (true) {
            PermRule child = expression(tokens, position);
            if (child == null || position[0] >= tokens.size()) {
                return null;
            }
            children.add(child);
            String next = tokens.get(position[0]++);
            if (next.equals(")")) {
                break;
            }
            if (!next.equals("且") && !next.equals("或") || joiner != null && !joiner.equals(next)) {
                return null;
            }
            joiner = next;
        }
        if (children.size() == 1) {
            return children.get(0);
        }
        return "且".equals(joiner) ? PermRule.all(children) : PermRule.any(children);
    }

    private static PermRule leaf(String token) {
        Matcher matcher = LEAF.matcher(token);
        if (!matcher.matches() || token.equals(")") || token.equals("且") || token.equals("或")) {
            return null;
        }
        if (matcher.group(2) == null) {
            return PermRule.code(matcher.group(1));
        }
        String param = matcher.group(3);
        return PermRule.scoped(matcher.group(1), matcher.group(2), ANY.equals(param) ? null : param);
    }
}
