package ai.neargo.commerce.persistence;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;

import java.sql.Statement;
import java.util.Objects;

/** Internal JDBC mechanics shared by typed repositories, with no business maps or host models. */
public final class JdbcInsert {
    private JdbcInsert() {}

    public static long insert(JdbcTemplate jdbc, String sql, Object... values) {
        var generated = new GeneratedKeyHolder();
        jdbc.update(
                connection -> {
                    var statement =
                            connection.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS);
                    for (int index = 0; index < values.length; index++) {
                        statement.setObject(index + 1, values[index]);
                    }
                    return statement;
                },
                generated);
        return Objects.requireNonNull(generated.getKey(), "Database did not return generated id")
                .longValue();
    }
}
