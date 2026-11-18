package ai.neargo.shop.arch;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ai.neargo.shop.auth.BizContext;
import ai.neargo.shop.auth.BizPerms;
import ai.neargo.shop.auth.PermChecker;
import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.context.expression.BeanFactoryResolver;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.expression.spel.standard.SpelExpressionParser;
import org.springframework.expression.spel.support.StandardEvaluationContext;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

/**
 * B 端「任一权限即可」只能写成 {@code @perm.canAnyBiz(...)}，不能把几个 {@code @perm.canBiz(...)} 用 {@code or} 连起来
 * （TDD-网关层与统一权限-开发计划 D3 清点时发现）。
 *
 * <p>{@code canBiz} 在缺权限时<b>抛异常</b>而不是返回 false（为了给出 70006 与「缺哪一样」），
 * 所以 {@code a or b} 里只要缺 a 就直接被拒，轮不到 b —— 写的是任一即可，实际只认第一个。
 * 曾有 10 个入口这样写，只有第二个权限的人被错误地拒掉。
 */
@SpringBootTest
@ActiveProfiles("test")
@DisplayName("B 端权限 · 任一即可只能用 canAnyBiz")
class BizAnyOfPermConventionTest {

    @Autowired
    @Qualifier("requestMappingHandlerMapping")
    RequestMappingHandlerMapping mapping;
    @Autowired
    PermChecker perm;
    @Autowired
    ApplicationContext context;

    @AfterEach
    void clear() {
        BizContext.clear();
    }

    @Test
    @DisplayName("★★★ 没有入口把 canBiz 用 or 连起来")
    void noEndpointJoinsCanBizWithOr() {
        Set<String> offenders = new TreeSet<>();
        mapping.getHandlerMethods().forEach((info, handler) -> {
            PreAuthorize annotation = AnnotatedElementUtils.findMergedAnnotation(handler.getMethod(), PreAuthorize.class);
            if (annotation == null) {
                annotation = AnnotatedElementUtils.findMergedAnnotation(handler.getBeanType(), PreAuthorize.class);
            }
            if (annotation != null && annotation.value().contains("canBiz(")
                    && annotation.value().matches("(?s).*(\\sor\\s|\\|\\|).*")) {
                offenders.add(handler.getBeanType().getSimpleName() + "#" + handler.getMethod().getName()
                        + " " + annotation.value());
            }
        });
        assertThat(offenders).as("改用 @perm.canAnyBiz('a','b')：canBiz 缺权限时抛异常，or 轮不到第二个").isEmpty();
    }

    @Test
    @DisplayName("★★★ 只有第二个权限的人：canAnyBiz 放行；旧写法在第一个上就抛 70006")
    void secondPermissionAloneIsEnough() {
        // 这家店上只给「看本店收款流水」，没有「发起原路退款」
        BizContext.set(new BizContext("ENTITY", Set.of(), Set.of(), Set.of("STORE"), "STORE", false,
                Map.of("STORE", Set.of("CUSTOM")), Map.of("STORE", Set.of(BizPerms.PAY_STATEMENT))));
        var evaluation = new StandardEvaluationContext();
        evaluation.setBeanResolver(new BeanFactoryResolver(context));
        var parser = new SpelExpressionParser();

        assertThat(parser.parseExpression("@perm.canAnyBiz('" + BizPerms.PAY_REFUND + "','" + BizPerms.PAY_STATEMENT
                + "')").getValue(evaluation, Boolean.class)).isTrue();

        assertThatThrownBy(() -> parser.parseExpression("@perm.canBiz('" + BizPerms.PAY_REFUND + "') or @perm.canBiz('"
                + BizPerms.PAY_STATEMENT + "')").getValue(evaluation, Boolean.class))
                .as("旧写法：缺第一个就抛，第二个根本没被问到")
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).errorCode()).isEqualTo(ErrorCode.BIZ_ROLE_FORBIDDEN);

        // 两个都没有时仍然拒绝，且给的是 70006
        BizContext.set(new BizContext("ENTITY", Set.of(), Set.of(), Set.of("STORE"), "STORE", false,
                Map.of("STORE", Set.of("CUSTOM")), Map.of("STORE", Set.of("biz:order:view"))));
        assertThatThrownBy(() -> perm.canAnyBiz(BizPerms.PAY_REFUND, BizPerms.PAY_STATEMENT))
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).errorCode()).isEqualTo(ErrorCode.BIZ_ROLE_FORBIDDEN);
    }
}
