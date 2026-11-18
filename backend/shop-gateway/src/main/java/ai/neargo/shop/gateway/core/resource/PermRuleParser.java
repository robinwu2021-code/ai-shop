package ai.neargo.shop.gateway.core.resource;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
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
 * 只认三种节点：{@code @perm.can('码')}、{@code and}、{@code or}。出现任何别的东西（方法参数、
 * 别的 bean、别的方法）就返回空 —— 调用方把这条链接标成「留给方法判」，提前只要求登录。
 * 宁可少判，不能判错：这里认错一个表达式，要么误拦，要么漏拦。
 */
public final class PermRuleParser {

    private static final Pattern PERM_CAN = Pattern.compile("^@perm\\.can\\('([^']+)'\\)$");
    private static final SpelExpressionParser PARSER = new SpelExpressionParser();

    private PermRuleParser() {
    }

    public static Optional<PermRule> parse(String expression) {
        try {
            SpelNode root = ((SpelExpression) PARSER.parseExpression(expression)).getAST();
            return Optional.ofNullable(convert(root));
        } catch (ParseException | ClassCastException e) {
            return Optional.empty();
        }
    }

    /** 认不出的节点返回 null，并一路传到顶。 */
    private static PermRule convert(SpelNode node) {
        if (node instanceof OpAnd || node instanceof OpOr) {
            List<PermRule> children = new ArrayList<>();
            for (int i = 0; i < node.getChildCount(); i++) {
                PermRule child = convert(node.getChild(i));
                if (child == null) {
                    return null;
                }
                children.add(child);
            }
            return node instanceof OpAnd ? PermRule.all(children) : PermRule.any(children);
        }
        Matcher leaf = PERM_CAN.matcher(node.toStringAST());
        return leaf.matches() ? PermRule.code(leaf.group(1)) : null;
    }
}
