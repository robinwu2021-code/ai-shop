package ai.neargo.shop.pay.svc;

import static org.assertj.core.api.Assertions.assertThat;

import ai.neargo.shop.common.config.ModuleDataSources;
import ai.neargo.shop.pay.mapper.SettleMappers.PaymentMapper;
import ai.neargo.shop.pay.mapper.storedvalue.SvAccountMapper;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.mybatis.spring.mapper.MapperFactoryBean;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest(classes = PayApplication.class, properties = {
        "shop.modules.member-marketing.datasource=rights",
        "shop.datasources.rights.url=jdbc:h2:mem:pay-rights;MODE=MySQL",
        "shop.datasources.rights.username=sa", "shop.datasources.rights.password="})
@ActiveProfiles("svctest")
class PayMemberMarketingDatabaseTest {
    @Autowired ApplicationContext context;

    @Test void mapperSessionsShareTheirOwnTransactionResource() {
        var rights = context.getBean(ModuleDataSources.MEMBER_MARKETING_TX_MANAGER, DataSourceTransactionManager.class);
        var pay = context.getBean("payTxManager", DataSourceTransactionManager.class);
        assertThat(rights.getDataSource()).isNotSameAs(pay.getDataSource());
        assertThat(mapperDataSource(SvAccountMapper.class)).isSameAs(rights.getDataSource());
        assertThat(mapperDataSource(PaymentMapper.class)).isSameAs(pay.getDataSource());
    }

    private DataSource mapperDataSource(Class<?> mapper) {
        String name = context.getBeanNamesForType(mapper)[0];
        var factory = context.getBean("&" + name, MapperFactoryBean.class);
        return factory.getSqlSessionFactory().getConfiguration().getEnvironment().getDataSource();
    }
}
