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
/*
 * ⚠️ **进销存的 Mapper 必须排除在外**。
 *
 * 这个扫描是按 `ai.neargo.shop` 全包扫的，而 `ai.neargo.shop.inventory` 走的是
 * **另一个数据源**（见 InventoryDataSourceConfig）。不排除的话，inv_* 的 Mapper 会被
 * 注册到平台的 SqlSessionFactory 上 —— 于是查 inv_stock_balance 打到 ai_shop 库，
 * 报的是「表不存在」，而排查方向会指向迁移没跑，不会指向数据源接错。
 *
 * 两头夹：这里排除，那边按 inventory.mapper 包显式绑到 invSqlSessionFactory。
 * 少任何一头都只在跑到那一行时才炸。
 */
@MapperScan(basePackages = "ai.neargo.shop",
        markerInterface = com.baomidou.mybatisplus.core.mapper.BaseMapper.class,
        excludeFilters = @org.springframework.context.annotation.ComponentScan.Filter(
                type = org.springframework.context.annotation.FilterType.REGEX,
                pattern = "ai\\.neargo\\.shop\\.inventory\\..*"))
/*
 * 第二个 @MapperScan：**不继承 BaseMapper 的 Mapper**。
 *
 * 上面那个用 markerInterface 限定只扫 BaseMapper 的子接口 —— 这是对的，
 * 它挡住了把随便一个接口当 Mapper 注册。但归档那种**不绑单一实体**的
 * Mapper（ai.neargo.shop.archive.ArchiveMapper，表名由调用方给）没法继承 BaseMapper，
 * 于是会被漏掉，表现是启动时 NoSuchBeanDefinitionException —— 编译期一点征兆都没有。
 *
 * 按 @Mapper 注解扫，范围收在 archive 包内：不放开到全局，
 * 免得又把「谁都能当 Mapper」这条口子开回来。
 */
@MapperScan(basePackages = {"ai.neargo.shop.archive", "ai.neargo.shop.media"},
        annotationClass = org.apache.ibatis.annotations.Mapper.class)
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
