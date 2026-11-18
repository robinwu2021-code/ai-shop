package ai.neargo.shop.gateway;

import static org.assertj.core.api.Assertions.assertThat;

import ai.neargo.shop.gateway.core.end.Access;
import ai.neargo.shop.gateway.core.resource.ApiResource;
import ai.neargo.shop.gateway.core.resource.PermRuleParser;
import ai.neargo.shop.gateway.core.resource.ResourceIndex;
import ai.neargo.shop.gateway.core.resource.ResourceIndexes;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

/**
 * B 端的资源索引与入库（TDD-网关层与统一权限-开发计划 D3）。
 */
@SpringBootTest
@ActiveProfiles("test")
@DisplayName("网关 · B 端资源索引")
class BizResourceIndexTest {

    @Autowired
    ResourceIndexes indexes;
    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    @Qualifier("requestMappingHandlerMapping")
    RequestMappingHandlerMapping mapping;

    @Test
    @DisplayName("★★★ 索引是全的；留给方法判的只有「是不是商家」那两种写法")
    void indexCoversEveryBizEndpoint() {
        ResourceIndex biz = indexes.of(List.of("/biz"));
        Set<String> registered = new TreeSet<>();
        mapping.getHandlerMethods().keySet().stream()
                .filter(info -> info.getPathPatternsCondition() != null)
                .forEach(info -> info.getPathPatternsCondition().getPatternValues().stream()
                        .filter(p -> p.startsWith("/biz/"))
                        .forEach(p -> {
                            var verbs = info.getMethodsCondition().getMethods();
                            if (verbs.isEmpty()) {
                                registered.add("* " + p);
                            }
                            verbs.forEach(verb -> registered.add(verb.name() + " " + p));
                        }));
        assertThat(biz.all().stream().map(r -> r.method() + " " + r.pattern()).toList())
                .containsExactlyInAnyOrderElementsOf(registered);

        long point = biz.all().stream().filter(r -> r.access() == Access.POINT).count();
        long defer = biz.all().stream().filter(r -> r.access() == Access.DEFER).count();
        long login = biz.all().stream().filter(r -> r.access() == Access.LOGIN).count();
        System.out.println("B 端资源清点：共 " + biz.all().size() + "，需功能点 " + point + "，留给方法判 " + defer
                + "，只需登录 " + login);
        assertThat(point).as("B 端大多数入口带按门店判的权限码").isGreaterThan(biz.all().size() * 2L / 3);

        // 结算单：单个权限码
        ApiResource bills = biz.locate("GET", "/biz/settle/bills").orElseThrow();
        assertThat(bills.access()).isEqualTo(Access.POINT);
        assertThat(bills.rule().codes()).hasSize(1);
    }

    @Test
    @DisplayName("★★ 运营端的写法与 B 端的写法互不相认：认错一个就会拿错的权限体系去判")
    void parserKeepsTheTwoEndsApart() {
        assertThat(PermRuleParser.parse("@perm.canBiz('biz:order')", PermRuleParser.OPS_METHODS)).isEmpty();
        assertThat(PermRuleParser.parse("@perm.can('order:read')", PermRuleParser.BIZ_METHODS)).isEmpty();
        assertThat(PermRuleParser.parse("@perm.merchantAccount()", PermRuleParser.BIZ_METHODS)).isEmpty();
        assertThat(PermRuleParser.parse("@managementAccess.authenticated()", PermRuleParser.BIZ_METHODS)).isEmpty();

        var any = PermRuleParser.parse("@perm.canAnyBiz('a','b','c')", PermRuleParser.BIZ_METHODS).orElseThrow();
        assertThat(any.codes()).containsExactly("a", "b", "c");
        assertThat(any.test(Set.of("b")::contains)).isTrue();
        assertThat(any.test(Set.of("x")::contains)).isFalse();

        var mixed = PermRuleParser.parse("@perm.canBiz('a') and (@perm.canBiz('b') or @perm.canBiz('c'))",
                PermRuleParser.BIZ_METHODS).orElseThrow();
        assertThat(mixed.test(Set.of("a", "c")::contains)).isTrue();
        assertThat(mixed.test(Set.of("b", "c")::contains)).isFalse();
    }

    @Test
    @DisplayName("★★ 启动同步：库里 biz 的行与索引条数一致，运营端的行不受影响")
    void bizRowsAreSynced() {
        ResourceIndex biz = indexes.of(List.of("/biz"));
        assertThat(jdbc.queryForObject("select count(*) from sys_api_resource where end_code='biz' and stale=0"
                + " and source='SCAN'", Integer.class)).isEqualTo(biz.all().size());
        assertThat(jdbc.queryForObject("select count(*) from sys_api_resource where end_code='ops' and stale=1",
                Integer.class)).isZero();
        assertThat(jdbc.queryForObject("select count(*) from sys_api_resource_point p join sys_api_resource r"
                + " on r.id = p.resource_id where r.end_code='biz'", Integer.class)).isPositive();
    }
}
