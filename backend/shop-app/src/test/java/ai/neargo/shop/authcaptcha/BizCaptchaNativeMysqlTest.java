package ai.neargo.shop.authcaptcha;

import ai.neargo.shop.auth.captcha.*;
import ai.neargo.shop.user.service.EmailOtpService;
import ai.neargo.shop.auth.captcha.impl.BizCaptchaServiceImpl;

import static org.assertj.core.api.Assertions.*;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.concurrent.*;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;

/** Disposable MySQL 9 only; intentionally separate from existing development schemas. */
@EnabledIfEnvironmentVariable(named="CAPTCHA_MYSQL_URL", matches="jdbc:mysql://127\\.0\\.0\\.1:[0-9]+/\\?.*")
class BizCaptchaNativeMysqlTest extends BizCaptchaServiceTest {
    JdbcTemplate admin;
    String schema;
    DataSourceTransactionManager transactions;
    @Override @BeforeEach void setup() throws Exception {
        String url = System.getenv("CAPTCHA_MYSQL_URL");
        String username = System.getenv().getOrDefault("CAPTCHA_MYSQL_USER","root");
        String password = System.getenv().getOrDefault("CAPTCHA_MYSQL_PASSWORD", "");
        admin = new JdbcTemplate(new DriverManagerDataSource(url, username, password));
        assertThat(admin.queryForObject("SELECT VERSION()",String.class)).startsWith("9.");
        schema = "captcha_test_" + UUID.randomUUID().toString().replace("-", "");
        admin.execute("CREATE DATABASE " + schema);
        var ds = new DriverManagerDataSource(url.replace("/?","/"+schema+"?"),username,password);
        jdbc = new JdbcTemplate(ds);
        var ddl = new ClassPathResource("db/migration/V663__biz_auth_captcha.sql").getContentAsString(StandardCharsets.UTF_8);
        for (String sql : ddl.split(";")) if (!sql.isBlank()) jdbc.execute(sql);
        transactions = new DataSourceTransactionManager(ds);
        store = new JdbcCaptchaStore(jdbc, transactions, clock);
        service = new BizCaptchaServiceImpl(store,new TianaiSliderEngine(),json,true,EmailOtpService::normalize);
    }
    @AfterEach void clean() { if (admin != null && schema != null) admin.execute("DROP DATABASE " + schema); }

    @Test void protectedBusinessRollbackDoesNotRestoreConsumedProof() {
        var c=service.issue(BizCaptchaService.Action.LOGIN,"rollback","7");
        var p=service.verify(c.challengeId(),BizCaptchaService.Action.LOGIN,"rollback",solution(c),"7");
        new TransactionTemplate(transactions).executeWithoutResult(status -> {
            service.require(BizCaptchaService.Action.LOGIN,"rollback",p.captchaToken());
            status.setRollbackOnly();
        });
        assertThatThrownBy(() -> service.require(BizCaptchaService.Action.LOGIN,"rollback",p.captchaToken()))
                .isInstanceOf(ai.neargo.shop.common.BizException.class);
    }

    @Test void rateLimitHasExactlyOneSharedCounterUnderConcurrency() throws Exception {
        try (var pool = Executors.newFixedThreadPool(8)) {
            var gate = new CountDownLatch(1);
            var futures = new java.util.ArrayList<Future<Boolean>>();
            for (int i=0;i<20;i++) futures.add(pool.submit(() -> {
                gate.await();
                try { store.limit("same-shared-window",10);return true; }
                catch (ai.neargo.shop.common.BizException e) { return false; }
            }));
            gate.countDown();
            int accepted=0;
            for(var future:futures) if(future.get()) accepted++;
            assertThat(accepted).isEqualTo(10);
        }
    }
}
