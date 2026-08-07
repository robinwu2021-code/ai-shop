package ai.neargo.shop.config;

import ai.neargo.common.data.scope.DataScopeHandler;
import ai.neargo.common.data.scope.DataScopeRegistrar;
import ai.neargo.common.data.scope.DataScopeTableRegistry;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.DataPermissionInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.OptimisticLockerInnerInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.PaginationInnerInterceptor;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

/**
 * MyBatis-Plus 装配：**数据权限 → 分页 → 乐观锁**，顺序不能换。
 *
 * <p>数据权限必须在分页之前：分页拦截器会把 SQL 改写成 count + limit 两条，
 * 排在它后面的数据权限只会作用到其中一条，于是 count 是全量、列表是过滤后的
 * —— 表现为「总数 100 条但只能翻 3 页」，且不报错。
 */
@Configuration
@MapperScan(basePackages = "ai.neargo.shop",
        markerInterface = com.baomidou.mybatisplus.core.mapper.BaseMapper.class)
public class MybatisPlusConfig {

    @Bean
    DataScopeTableRegistry dataScopeTableRegistry(List<DataScopeRegistrar> registrars) {
        DataScopeTableRegistry registry = new DataScopeTableRegistry();
        registrars.forEach(r -> r.register(registry));
        return registry;
    }

    @Bean
    DataScopeHandler dataScopeHandler(DataScopeTableRegistry registry) {
        return new DataScopeHandler(registry);
    }

    @Bean
    MybatisPlusInterceptor mybatisPlusInterceptor(DataScopeHandler dataScopeHandler) {
        MybatisPlusInterceptor interceptor = new MybatisPlusInterceptor();
        interceptor.addInnerInterceptor(new DataPermissionInterceptor(dataScopeHandler));
        interceptor.addInnerInterceptor(new PaginationInnerInterceptor());
        interceptor.addInnerInterceptor(new OptimisticLockerInnerInterceptor());
        return interceptor;
    }
}
