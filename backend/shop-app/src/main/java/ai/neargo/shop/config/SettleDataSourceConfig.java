package ai.neargo.shop.config;

import com.baomidou.mybatisplus.core.config.GlobalConfig;
import com.baomidou.mybatisplus.core.handlers.MetaObjectHandler;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
import com.zaxxer.hikari.HikariDataSource;
import org.apache.ibatis.session.SqlSessionFactory;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.PlatformTransactionManager;

import javax.sql.DataSource;

/**
 * 支付域（今天还叫 {@code shop-settle}）的<b>独立数据源与事务管理器</b>。
 *
 * <h2>它解决什么：让跨域事务在物理上写不出来</h2>
 * 支付域独立成进程之后就没有共享事务了。而在此之前，
 * 「下单顺手把结算单也写了」这种写法<b>在单体里看不出任何问题</b> ——
 * 一起提交、一起回滚。代价要等到拆分那天才付。
 *
 * <p>这个配置把它变成<b>做不到</b>：业务的 {@code @Transactional} 用平台的事务管理器，
 * 支付域的用这一个，两者管的是不同的连接。于是跨域事务想写也写不出来 ——
 * 而这正是拆分之后的真实语义。
 *
 * <p><b>URL 仍然指向主库</b>：这一步只解事务耦合，不切库。
 * 表还在 {@code ai_shop} 里，迁移还走平台那套 Flyway，所以
 * <b>不需要第二个 Flyway，也不需要新的库或账号</b> ——
 * 它是一次纯装配改动，配置文件都不用改。
 *
 * <h2>与 {@code InventoryDataSourceConfig} 的三处异同</h2>
 * <ol>
 *   <li><b>同样不是 {@code @Primary}</b>：平台那套仍是默认数据源，注入点一个都不受影响。</li>
 *   <li><b>反过来：这里必须装 DataScope 拦截器。</b> 进销存那边刻意不装，
 *       因为它只认 {@code ownerId}；而 {@code stl_bill} / {@code stl_withdraw} /
 *       {@code stl_purchase_invoice} / {@code stl_settle_invoice}
 *       <b>都已经注册进数据域</b>（见 {@code DataScopeRegistration}）。
 *       不装的话，给运营配的「只看某商家」对结算单整片失效 ——
 *       <b>那是越权，而且不报错</b>。所以这里注入的是与平台<b>同一个</b>
 *       {@code MybatisPlusInterceptor} bean。</li>
 *   <li><b>不需要自己的 Flyway</b>：库是同一个。进销存那条「Flyway 凭证 bean
 *       不能是 Flyway 类型」的坑在这里不存在 —— 因为这里根本不建 Flyway。</li>
 * </ol>
 *
 * <h2>Mapper 的归属靠两头夹</h2>
 * 这里按 {@code settle.mapper} 包显式绑到本工厂，
 * {@code MybatisPlusConfig} 那个全局扫描把 {@code ai.neargo.shop.settle} 排除掉。
 * <b>少任何一头，结算的查询都会打回平台的工厂上</b> —— 而那时事务隔离静默失效，
 * 表现与今天一模一样（因为库是同一个），只有拆库那天才会炸。
 *
 * <p>{@code CrossDomainTxConventionTest} 盯着这件事：settle 里的
 * {@code @Transactional} 必须点名 {@code settleTxManager}，漏一个就红。
 */
@Configuration
@MapperScan(basePackages = "ai.neargo.shop.settle.mapper",
        sqlSessionFactoryRef = "settleSqlSessionFactory")
public class SettleDataSourceConfig {

    /**
     * 与平台同一个库。
     *
     * <p>刻意直接复用 {@code spring.datasource.*} 而不新开一组配置项：
     * 这一步不切库，多一组配置只会让人以为可以往那儿填另一个地址 ——
     * 而真到切库那天，要改的远不止一个 URL（还有独立账号、独立 Flyway、
     * 跨库引用改成业务键 + 快照）。见 ADR-021 阶段 2。
     */
    @Bean
    DataSource settleDataSource(
            @Value("${spring.datasource.url}") String url,
            @Value("${spring.datasource.username}") String username,
            @Value("${spring.datasource.password}") String password,
            @Value("${spring.datasource.driver-class-name}") String driver) {
        HikariDataSource ds = new HikariDataSource();
        ds.setJdbcUrl(url);
        ds.setUsername(username);
        ds.setPassword(password);
        ds.setDriverClassName(driver);
        /*
         * 池子给小的：支付域的写都是短事务（生成结算单、写一条流水），
         * 而它与平台池**共享同一个库的连接上限**。给大了是从业务那边抢连接，
         * 症状是高峰期下单变慢，而原因指向一个刚刚才加上的池子。
         */
        ds.setMaximumPoolSize(8);
        ds.setPoolName("settle-pool");
        return ds;
    }

    /**
     * 支付域自己的事务管理器。
     *
     * <p><b>它就是这整个配置的目的</b>：业务的 {@code @Transactional}
     * 管不到这一个的连接，于是「下单事务里顺手写结算单并一起回滚」
     * 从「能写但不该写」变成「写不出来」。
     */
    @Bean
    PlatformTransactionManager settleTxManager(
            @Qualifier("settleDataSource") DataSource ds) {
        return new DataSourceTransactionManager(ds);
    }

    /**
     * @param interceptor <b>平台的那一个</b>，不是新建的 ——
     *                    数据域、分页、乐观锁三个拦截器都要跟过来。
     *                    新建一个只装分页的话，结算单的数据域会静默失效。
     */
    @Bean
    SqlSessionFactory settleSqlSessionFactory(@Qualifier("settleDataSource") DataSource ds,
                                              MybatisPlusInterceptor interceptor,
                                              MetaObjectHandler metaObjectHandler)
            throws Exception {
        MybatisSqlSessionFactoryBean bean = new MybatisSqlSessionFactoryBean();
        bean.setDataSource(ds);
        bean.setPlugins(interceptor);
        /*
         * **填充器必须装。** 不装的话 MyBatis-Plus 会把 null 显式写进 INSERT，
         * 顶掉 DDL 上的 DEFAULT CURRENT_TIMESTAMP —— 报的是
         * 「NULL not allowed for column "created_at"」，而错误里没有一个字
         * 提到数据源装配。（第一次跑测试就是栽在这里。）
         *
         * ⚠️ **必须新建 GlobalConfig，不能用 GlobalConfigUtils.defaults()**：
         * 那个方法返回的是共享实例，在它上面 setMetaObjectHandler 会一路污染到
         * 平台那套工厂。这一条是进销存那边写下的，照抄。
         */
        GlobalConfig global = new GlobalConfig();
        global.setDbConfig(new GlobalConfig.DbConfig());
        global.setMetaObjectHandler(metaObjectHandler);
        bean.setGlobalConfig(global);
        return bean.getObject();
    }
}
