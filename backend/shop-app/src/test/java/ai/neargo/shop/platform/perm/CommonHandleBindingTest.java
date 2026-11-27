package ai.neargo.shop.platform.perm;

import static org.assertj.core.api.Assertions.assertThat;

import ai.neargo.shop.common.config.ModuleDataSources;
import ai.neargo.shop.platform.perm.mapper.PermMappers;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.beans.factory.config.RuntimeBeanReference;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

/**
 * 权限的五个 Mapper 挂在基础能力句柄 {@code common} 上（TDD-基础能力库对齐 2a、Q2）。
 *
 * <p>没绑定数据源时 {@code commonSqlSessionFactory} 是主库工厂的别名，比对象看不出区别；
 * 所以比的是 Mapper 注册时点名的工厂名 —— 绑定那天它决定读写哪个库。
 */
@SpringBootTest
@ActiveProfiles("test")
class CommonHandleBindingTest {

    @Autowired
    private ConfigurableListableBeanFactory beans;

    @Test
    @DisplayName("权限 Mapper 点名 commonSqlSessionFactory，不在主库工厂上")
    void permissionMappersAreRegisteredOnTheCommonHandle() {
        for (Class<?> type : List.of(PermMappers.FunctionMapper.class, PermMappers.FunctionPointMapper.class,
                PermMappers.RoleMapper.class, PermMappers.RolePointMapper.class, PermMappers.RoleMemberMapper.class)) {
            String[] names = beans.getBeanNamesForType(type);
            assertThat(names).as(type.getSimpleName()).hasSize(1);
            String mapper = names[0];
            Object ref = beans.getBeanDefinition(mapper).getPropertyValues().get("sqlSessionFactory");
            assertThat(ref).as(mapper).isInstanceOf(RuntimeBeanReference.class);
            assertThat(((RuntimeBeanReference) ref).getBeanName()).as(mapper)
                    .isEqualTo(ModuleDataSources.COMMON_SQL_SESSION_FACTORY);
        }
        assertThat(ModuleDataSources.MODULES).contains(ModuleDataSources.COMMON);
    }
}
