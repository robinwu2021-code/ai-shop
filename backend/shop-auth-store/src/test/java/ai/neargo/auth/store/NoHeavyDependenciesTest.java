package ai.neargo.auth.store;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.fail;

/**
 * 依赖清单是这个模块的**设计的一部分**，所以要有守卫。
 *
 * <p>会绊人的地方：误引一个 starter 之后**什么都不会报错** ——
 * 编译过、测试绿、跑起来也正常。只有等到把这个模块嵌进一个没有 MyBatis 的服务
 * （比如 shop-job）时才会发现它把整套平台持久层带了过去，
 * 而那时候已经很难说清是哪一次提交引进来的。
 *
 * <p>用 {@code Class.forName} 而不是解析依赖树：它检的是**真实 classpath**，
 * 传递依赖也躲不掉。
 */
class NoHeavyDependenciesTest {

    @Test
    @DisplayName("★ 不能有 MyBatis —— 引了 shop-base 就会把它带进来")
    void noMyBatis() {
        assertAbsent("com.baomidou.mybatisplus.core.MybatisConfiguration",
                "MyBatis-Plus 进了 classpath。多半是引了 shop-base（它把 mybatis-plus starter "
                + "作为编译依赖）。而本模块存在的全部意义，是让任何服务都能嵌入鉴权"
                + "而不必把平台持久层拖进来 —— 引了 shop-base，这一条当场作废");
        assertAbsent("org.apache.ibatis.session.SqlSessionFactory", "MyBatis 进了 classpath");
    }

    @Test
    @DisplayName("★ 不能有 shop-base 的任何类型")
    void noShopBase() {
        assertAbsent("ai.neargo.shop.auth.LoginUser",
                "shop-base 进了 classpath。IdentityLoader 之所以是泛型，就是为了不认识 LoginUser");
        assertAbsent("ai.neargo.shop.auth.TokenStore", "shop-base 进了 classpath");
    }

    @Test
    @DisplayName("不能有 Spring Data —— 本模块用 JdbcClient，不用 repository 代理")
    void noSpringData() {
        assertAbsent("org.springframework.data.repository.CrudRepository",
                "Spring Data 进了 classpath。多半把 spring-boot-starter-jdbc 写成了 "
                + "spring-boot-starter-data-jdbc。差别不只是几个 jar：repository 是代理，"
                + "而代理正是 native 最难处理的东西");
    }

    @Test
    @DisplayName("JdbcClient 必须在 —— 它是本模块唯一的数据访问入口")
    void jdbcClientIsPresent() {
        try {
            Class.forName("org.springframework.jdbc.core.simple.JdbcClient");
        } catch (ClassNotFoundException e) {
            fail("JdbcClient 不在 classpath 上，spring-jdbc 没引进来？");
        }
    }

    private static void assertAbsent(String className, String why) {
        try {
            Class.forName(className);
            fail(why + "（发现 " + className + "）");
        } catch (ClassNotFoundException expected) {
            // 正是我们要的
        }
    }
}
