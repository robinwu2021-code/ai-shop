package ai.neargo.shop.gateway.core.resource;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.expression.ParseException;
import org.springframework.expression.spel.SpelNode;
import org.springframework.expression.spel.ast.OpAnd;
import org.springframework.expression.spel.ast.OpOr;
import org.springframework.expression.spel.standard.SpelExpression;
import org.springframework.expression.spel.standard.SpelExpressionParser;

/**
 * 把 {@code @PreAuthorize} 的表达式解析成 {@link PermRule}。
 *
 * <p><b>用 Spring 自己的表达式解析器取语法树，不用正则切字符串</b>：括号与优先级由解析器负责。
 * 只认三种节点：权限检查器上的一次调用（哪几个方法算数由调用方给，运营端是 {@code can}，
 * B 端是 {@code canBiz} 与「任一即可」的 {@code canAnyBiz}）、{@code and}、{@code or}。出现任何别的东西（方法参数、
 * 别的 bean、别的方法）就返回空 —— 调用方把这条链接标成「留给方法判」，提前只要求登录。
 * 宁可少判，不能判错：这里认错一个表达式，要么误拦，要么漏拦。
 */
public final class PermRuleParser {

    /** 运营端的写法。 */
    public static final Set<String> OPS_METHODS = Set.of("can");
    /**
     * B 端的写法：按当前门店判的 {@code canBiz}、任一即可的 {@code canAnyBiz}，
     * 以及按管理空间判的 {@code canManage(码, 类型, #参数)} 与 {@code canManageAny(码, 类型)}。
     */
    public static final Set<String> BIZ_METHODS = Set.of("canBiz", "canAnyBiz", "canManage", "canManageAny");

    /** {@code @perm.canManage('码','类型',#参数)}：参数必须是一个方法参数的引用，不能是表达式 */
    private static final Pattern MANAGE_ON =
            Pattern.compile("^@perm\\.canManage\\('([^']+)','([A-Z_]+)',#([A-Za-z_][A-Za-z0-9_]*)\\)$");
    private static final Pattern MANAGE_ANY =
            Pattern.compile("^@perm\\.canManageAny\\('([^']+)','([A-Z_]+)'\\)$");

    /**
     * B 端上只问「是不是商家」的两种写法。B 端的认证链只收商家令牌（{@code MerchantTokenAuthFilter}），
     * 登录了就一定是商家，所以它们等同于「只要求登录」。只认整条表达式恰好是其中之一，不认它们与别的东西的组合。
     */
    private static final Set<String> BIZ_LOGIN_ONLY = Set.of("@perm.merchantAccount()", "@managementAccess.authenticated()");

    private static final Pattern PERM_CALL = Pattern.compile("^@perm\\.(\\w+)\\(('[^']+'(?:,'[^']+')*)\\)$");
    private static final Pattern QUOTED = Pattern.compile("'([^']+)'");
    private static final SpelExpressionParser PARSER = new SpelExpressionParser();

    private PermRuleParser() {
    }

    /** B 端：整条表达式是不是只在问「是不是商家」。 */
    public static boolean bizLoginOnly(String expression) {
        return expression != null && BIZ_LOGIN_ONLY.contains(expression.replaceAll("\\s+", ""));
    }

    public static Optional<PermRule> parse(String expression) {
        return parse(expression, OPS_METHODS);
    }

    /** @param methods 权限检查器上哪几个方法算作「要这个权限码」；别的方法一律认不出 */
    public static Optional<PermRule> parse(String expression, Set<String> methods) {
        try {
            SpelNode root = ((SpelExpression) PARSER.parseExpression(expression)).getAST();
            return Optional.ofNullable(convert(root, methods));
        } catch (ParseException | ClassCastException e) {
            return Optional.empty();
        }
    }

    /** 认不出的节点返回 null，并一路传到顶。 */
    private static PermRule convert(SpelNode node, Set<String> methods) {
        if (node instanceof OpAnd || node instanceof OpOr) {
            List<PermRule> children = new ArrayList<>();
            for (int i = 0; i < node.getChildCount(); i++) {
                PermRule child = convert(node.getChild(i), methods);
                if (child == null) {
                    return null;
                }
                children.add(child);
            }
            return node instanceof OpAnd ? PermRule.all(children) : PermRule.any(children);
        }
        String ast = node.toStringAST();
        Matcher manageOn = MANAGE_ON.matcher(ast);
        if (manageOn.matches()) {
            return methods.contains("canManage") ? PermRule.scoped(manageOn.group(1), manageOn.group(2), manageOn.group(3))
                    : null;
        }
        Matcher manageAny = MANAGE_ANY.matcher(ast);
        if (manageAny.matches()) {
            return methods.contains("canManageAny") ? PermRule.scoped(manageAny.group(1), manageAny.group(2), null)
                    : null;
        }
        Matcher leaf = PERM_CALL.matcher(ast);
        // canManage / canManageAny 只认上面两种精确的形状；写成别的样子（少一个参数、范围是表达式）一律认不出
        if (!leaf.matches() || !methods.contains(leaf.group(1)) || leaf.group(1).startsWith("canManage")) {
            return null;
        }
        List<PermRule> codes = new ArrayList<>();
        Matcher quoted = QUOTED.matcher(leaf.group(2));
        while (quoted.find()) {
            codes.add(PermRule.code(quoted.group(1)));
        }
        boolean anyOf = leaf.group(1).startsWith("canAny");
        if (codes.size() == 1 && !anyOf) {
            return codes.get(0);
        }
        // 只有「任一即可」的方法才收多个码；can('a','b') 这种写法不存在，认不出就留给方法判
        return anyOf ? PermRule.any(codes) : null;
    }
}
