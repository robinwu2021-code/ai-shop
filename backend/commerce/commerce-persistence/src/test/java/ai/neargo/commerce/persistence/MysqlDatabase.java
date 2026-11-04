package ai.neargo.commerce.persistence;

import org.flywaydb.core.Flyway;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import javax.sql.DataSource;

final class MysqlDatabase implements AutoCloseable {
    final String name = "commerce_test_" + java.util.UUID.randomUUID().toString().replace("-", "");
    final DataSource admin;
    final DataSource ds;
    final JdbcTemplate jdbc;

    MysqlDatabase() {
        String url = required("COMMERCE_MYSQL_URL");
        String user = required("COMMERCE_MYSQL_USER"), pass = required("COMMERCE_MYSQL_PASSWORD");
        admin = new DriverManagerDataSource(url, user, pass);
        new JdbcTemplate(admin).execute("CREATE DATABASE " + name);
        ds = new DriverManagerDataSource(url.replace("?", "" + name + "?"), user, pass);
        jdbc = new JdbcTemplate(ds);
    }

    static String required(String key) {
        String v = System.getenv(key);
        if (v == null || v.isBlank())
            throw new IllegalStateException("Real MySQL required: set " + key);
        return v;
    }

    void migrate() {
        Flyway.configure()
                .dataSource(ds)
                .table("commerce_schema_history")
                .locations("classpath:db/commerce/migration")
                .load()
                .migrate();
    }

    public void close() {
        new JdbcTemplate(admin).execute("DROP DATABASE " + name);
    }
}
