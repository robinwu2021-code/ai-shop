package ai.neargo.shop.commercebridge.approval;

import static org.junit.jupiter.api.Assertions.*;

import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

@EnabledIfEnvironmentVariable(named="COMMERCE_MYSQL_URL", matches="jdbc:mysql://127\\.0\\.0\\.1:3308/.*")
class MallApprovalSubjectsMysqlTest {
    @Test
    void scopesAndApprovalRevisionsUseTheOwningDatabases() {
        String url = System.getenv("COMMERCE_MYSQL_URL"), user = System.getenv("COMMERCE_MYSQL_USER"),
                password = System.getenv("COMMERCE_MYSQL_PASSWORD");
        var admin = new JdbcTemplate(new DriverManagerDataSource(url, user, password));
        String suffix = UUID.randomUUID().toString().replace("-", "");
        String mall = "mall_approval_" + suffix, platform = "platform_approval_" + suffix;
        admin.execute("CREATE DATABASE " + mall);
        try {
            admin.execute("CREATE DATABASE " + platform);
            try {
                var mallSource = new DriverManagerDataSource(url.replace("/?", "/" + mall + "?"), user, password);
                var platformSource = new DriverManagerDataSource(url.replace("/?", "/" + platform + "?"), user, password);
                var beans = new DefaultListableBeanFactory();
                beans.registerSingleton("shopMallDataSource", mallSource);
                beans.registerSingleton("platformDataSource", platformSource);
                var sources = new MallGateSubject.Databases(admin, beans, "shopMall");
                sources.mall.execute("CREATE TABLE mall_order(tenant_no TEXT,store_no TEXT,order_no TEXT,version INT,pay_status TEXT)");
                sources.mall.execute("CREATE TABLE mall_refund(tenant_no TEXT,store_no TEXT,order_no TEXT,refund_no TEXT,version INT,status TEXT,reason TEXT,requested_cash_minor INT,policy_snapshot JSON)");
                sources.mall.execute("CREATE TABLE mall_offline_payment(tenant_no TEXT,order_no TEXT,claim_no TEXT,action_no TEXT,record_type TEXT,action_snapshot JSON)");
                sources.platform.execute("CREATE TABLE mch_store(tenant_no TEXT,entity_no TEXT,store_no TEXT,deleted INT)");
                sources.platform.execute("INSERT INTO mch_store VALUES ('other','wrong','S',0),('T','E','S',0)");
                sources.mall.execute("INSERT INTO mall_order VALUES ('T','S','O',3,'PENDING')");
                sources.mall.execute("INSERT INTO mall_refund VALUES ('T','S','O','R',1,'REQUESTED','reason',25,JSON_OBJECT('refundNo','R'))");
                sources.mall.execute("INSERT INTO mall_offline_payment VALUES ('T','O','A','A','CLAIM',JSON_OBJECT('amount',25))");
                var offline = new MallOfflinePaymentApprovalSubject(sources);
                var action = new MallGateSubject.Ref("T","S","O","A");
                assertEquals(Set.of("merchant:E","store:S"), offline.scopes(action));
                assertTrue(offline.state(action).contains("A="));
                assertTrue(offline.awaiting(action));
                sources.mall.execute("INSERT INTO mall_offline_payment VALUES ('T','O','A','A','ACTION',JSON_OBJECT())");
                assertFalse(offline.awaiting(action));
                var refunds = new MallRefundApprovalSubject(sources);
                var refund = new MallGateSubject.Ref("T","S","O","R");
                assertTrue(refunds.awaiting(refund));
                assertEquals("1:REQUESTED",refunds.state(refund));
                sources.mall.execute("UPDATE mall_refund SET policy_snapshot=JSON_OBJECT()");
                assertFalse(refunds.awaiting(refund));
            } finally {
                admin.execute("DROP DATABASE " + platform);
            }
        } finally {
            admin.execute("DROP DATABASE " + mall);
        }
    }
}
