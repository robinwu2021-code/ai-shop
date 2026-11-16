package ai.neargo.shop.user;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

import ai.neargo.shop.user.mapper.UserMappers.IdentityMapper;
import ai.neargo.shop.user.mapper.UserMappers.UserMapper;
import ai.neargo.shop.user.port.UserProvisionPortImpl;
import ai.neargo.shop.user.service.AuthService;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
import java.util.UUID;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/** Real mapper filtering: registration ownership is separate from email verification. */
class UserProvisionEmailLookupTest {
    private JdbcTemplate jdbc;
    private UserProvisionPortImpl provision;
    private final AuthService auth = mock(AuthService.class);

    @BeforeEach
    void setup() throws Exception {
        var ds = new DriverManagerDataSource(
                "jdbc:h2:mem:owner_" + UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", "");
        jdbc = new JdbcTemplate(ds);
        jdbc.execute("CREATE TABLE usr_identity(id BIGINT AUTO_INCREMENT PRIMARY KEY, tenant_no VARCHAR(32),"
                + "user_no VARCHAR(64),identity_type VARCHAR(32),identity_value VARCHAR(255),channel VARCHAR(32),"
                + "verified_at TIMESTAMP,created_at TIMESTAMP,created_by VARCHAR(64),updated_at TIMESTAMP,"
                + "updated_by VARCHAR(64),version BIGINT DEFAULT 0,deleted INT DEFAULT 0)");
        jdbc.execute("CREATE TABLE usr_account(id BIGINT AUTO_INCREMENT PRIMARY KEY,tenant_no VARCHAR(32),"
                + "user_no VARCHAR(64),status VARCHAR(32),deleted INT DEFAULT 0)");
        var config = new MybatisConfiguration();
        config.setMapUnderscoreToCamelCase(true);
        config.addMapper(IdentityMapper.class);
        config.addMapper(UserMapper.class);
        var factory = new MybatisSqlSessionFactoryBean();
        factory.setDataSource(ds);
        factory.setConfiguration(config);
        var session = new SqlSessionTemplate(factory.getObject());
        provision = new UserProvisionPortImpl(auth, session.getMapper(IdentityMapper.class), session.getMapper(UserMapper.class));
        jdbc.update("INSERT INTO usr_account(tenant_no,user_no,status) VALUES('MAIN','U-SEED','NORMAL')");
        jdbc.update("INSERT INTO usr_identity(tenant_no,user_no,identity_type,identity_value) "
                + "VALUES('MAIN','U-SEED','EMAIL','owner@example.test')");
    }

    @Test
    void unverifiedRegisteredOwnerResolvesWithoutVerifyingOrProvisioning() {
        assertThat(provision.registeredUserByEmail(" Owner@Example.Test ")).contains("U-SEED");
        assertThat(jdbc.queryForObject("SELECT verified_at FROM usr_identity", Object.class)).isNull();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM usr_identity", Long.class)).isEqualTo(1L);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM usr_account", Long.class)).isEqualTo(1L);
        verifyNoInteractions(auth);
    }

    @Test
    void verifiedRegisteredOwnerStillResolves() {
        jdbc.update("UPDATE usr_identity SET verified_at=CURRENT_TIMESTAMP");
        assertThat(provision.registeredUserByEmail("owner@example.test")).contains("U-SEED");
    }

    @Test
    void unavailableAndDeletedAccountsAreNeverOwners() {
        jdbc.update("UPDATE usr_identity SET verified_at=CURRENT_TIMESTAMP");
        for (String status : List.of("BANNED", "RISK_LIMITED", "FROZEN")) {
            jdbc.update("UPDATE usr_account SET status=?", status);
            assertThat(provision.registeredUserByEmail("owner@example.test")).isEmpty();
        }
        jdbc.update("UPDATE usr_account SET status='NORMAL',deleted=1");
        assertThat(provision.registeredUserByEmail("owner@example.test")).isEmpty();
        jdbc.update("DELETE FROM usr_account");
        assertThat(provision.registeredUserByEmail("owner@example.test")).isEmpty();
    }

    @Test
    void missingAndDeletedEmailIdentitiesAreRejected() {
        assertThat(provision.registeredUserByEmail("missing@example.test")).isEmpty();
        jdbc.update("UPDATE usr_identity SET verified_at=CURRENT_TIMESTAMP,deleted=1");
        assertThat(provision.registeredUserByEmail("owner@example.test")).isEmpty();
    }

    @Test
    void identityAndAccountMustBothBelongToTheMainTenant() {
        jdbc.update("UPDATE usr_identity SET verified_at=CURRENT_TIMESTAMP,tenant_no='OTHER'");
        assertThat(provision.registeredUserByEmail("owner@example.test")).isEmpty();
        jdbc.update("UPDATE usr_identity SET tenant_no='MAIN'");
        jdbc.update("UPDATE usr_account SET tenant_no='OTHER'");
        assertThat(provision.registeredUserByEmail("owner@example.test")).isEmpty();
    }
}
