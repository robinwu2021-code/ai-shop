package ai.neargo.shop.arch;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.TreeSet;
import java.util.stream.Collectors;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

/**
 * 全部端点的快照：方法、地址、处理类与方法、权限表达式（TDD-网关层与统一权限-开发计划 M0 任务 0.2）。
 *
 * <p>网关层重构的前四个里程碑（搬文件、换装配、每端隔离）都承诺不改变对外行为。这份快照是那句承诺的证据：
 * 重构前后各跑一遍，逐行相同。比对方式见 {@link GatewaySnapshot}。
 *
 * <p>端点取自 {@code RequestMappingHandlerMapping} 而不是扫源码：搬文件之后源码路径全变了，
 * 而 Spring 实际登记了什么才是对外行为。{@code test} 配置同时装了 api 与 ops 两套面，所以三端都在里面。
 */
@SpringBootTest
@ActiveProfiles("test")
@DisplayName("网关重构 · 端点快照")
class EndpointSnapshotTest {

    @Autowired
    @Qualifier("requestMappingHandlerMapping")
    RequestMappingHandlerMapping mapping;

    @Test
    @DisplayName("端点清单落盘；给了基线就逐行比对")
    void endpointsMatchBaseline() {
        List<String> lines = new ArrayList<>(snapshot());

        assertThat(lines).as("一个端点都没取到，说明取数方式失效了").isNotEmpty();
        for (String prefix : List.of("/mp/", "/biz/", "/ops/", "/internal/")) {
            assertThat(lines).as("快照里应当有 %s 下的端点", prefix).anyMatch(line -> line.contains(" " + prefix));
        }
        assertThat(lines).as("权限表达式要进快照，否则改注解不会被发现")
                .anyMatch(line -> line.contains("@perm.can("));

        GatewaySnapshot.writeAndCompare("endpoints.txt", lines);
    }

    /** 一行一个「方法 + 地址」；同一个处理方法挂多个地址或多个方法时各占一行。 */
    private TreeSet<String> snapshot() {
        TreeSet<String> out = new TreeSet<>();
        mapping.getHandlerMethods().forEach((info, handler) -> {
            var patterns = info.getPathPatternsCondition();
            if (patterns == null) {
                return;
            }
            List<String> verbs = info.getMethodsCondition().getMethods().stream().map(Enum::name).sorted().toList();
            for (String pattern : patterns.getPatternValues()) {
                for (String verb : verbs.isEmpty() ? List.of("*") : verbs) {
                    out.add(verb + " " + pattern + " | " + describe(handler) + " | " + preAuthorize(handler));
                }
            }
        });
        return out;
    }

    private static String describe(HandlerMethod handler) {
        String params = java.util.Arrays.stream(handler.getMethod().getParameterTypes())
                .map(Class::getSimpleName).collect(Collectors.joining(","));
        return handler.getBeanType().getName() + "#" + handler.getMethod().getName() + "(" + params + ")";
    }

    /** 方法上的优先，其次类上的；都没有记成 -。 */
    private static String preAuthorize(HandlerMethod handler) {
        PreAuthorize onMethod = AnnotatedElementUtils.findMergedAnnotation(handler.getMethod(), PreAuthorize.class);
        if (onMethod != null) {
            return onMethod.value();
        }
        PreAuthorize onType = AnnotatedElementUtils.findMergedAnnotation(handler.getBeanType(), PreAuthorize.class);
        return onType == null ? "-" : onType.value();
    }
}
