package ai.neargo.shop.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.zaxxer.hikari.HikariDataSource;
import javax.sql.DataSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * 主库、支付、密钥三个连接池的大小可配（TDD-网关层与统一权限-开发计划 §2.6b 第 5 条）。
 *
 * <p>这三个池连的是同一个库、共享同一个连接上限。此前主库池不认 {@code spring.datasource.hikari.*}、
 * 另两个写死在代码里，一个实例一启动就占 22 个连接，生产上只能靠调大库的上限顶过去。
 */
@SpringBootTest(properties = {
        "spring.datasource.hikari.maximum-pool-size=3",
        "spring.datasource.hikari.minimum-idle=1",
        "shop.pay.pool.max-size=2",
        "shop.pay.pool.min-idle=0",
        "shop.secret.pool.min-idle=9"})
@ActiveProfiles("test")
@DisplayName("连接池 · 大小可配")
class ConfigurablePoolsTest {

    @Autowired
    @Qualifier("dataSource")
    DataSource primary;
    @Autowired
    @Qualifier("payDataSource")
    DataSource pay;
    @Autowired
    @Qualifier("secretDataSource")
    DataSource secret;

    @Test
    @DisplayName("★★★ 主库池认 spring.datasource.hikari.*；按需读账号的小池不受影响")
    void primaryPoolBindsHikariProperties() {
        HikariDataSource main = (HikariDataSource) ReflectionTestUtils.getField(primary, "primary");
        assertThat(main.getMaximumPoolSize()).isEqualTo(3);
        assertThat(main.getMinimumIdle()).isEqualTo(1);
        HikariDataSource fresh = (HikariDataSource) ReflectionTestUtils.getField(primary, "fresh");
        assertThat(fresh.getMaximumPoolSize()).isEqualTo(2);
        assertThat(fresh.getMinimumIdle()).isZero();
    }

    @Test
    @DisplayName("★★★ 支付池、密钥池的上限与空闲数可配；没配的那一项保持原来的默认")
    void payAndSecretPoolsAreConfigurable() {
        HikariDataSource payPool = (HikariDataSource) pay;
        assertThat(payPool.getMaximumPoolSize()).isEqualTo(2);
        assertThat(payPool.getMinimumIdle()).isZero();
        HikariDataSource secretPool = (HikariDataSource) secret;
        assertThat(secretPool.getMaximumPoolSize()).as("没配上限：还是 4").isEqualTo(4);
        assertThat(secretPool.getMinimumIdle()).as("空闲数不会超过上限").isEqualTo(4);
    }
}
