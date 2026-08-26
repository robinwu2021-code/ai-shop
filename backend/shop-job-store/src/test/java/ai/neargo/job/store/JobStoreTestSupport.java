package ai.neargo.job.store;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.SimpleDriverDataSource;

import javax.sql.DataSource;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.Statement;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 起一个 H2 内存库，用**生成的 H2 等价脚本**建表。
 *
 * <p><b>刻意不在本模块留一份 schema 副本。</b>副本会腐烂 ——
 * 改了源迁移而忘了同步副本，测试照样绿，而绿的是一张过时的表。
 * 这里直接读 {@code shop-app/src/test/resources/db/job-h2/}（由
 * {@code scripts/gen-test-schema.py} 从 {@code db/job} 重放生成，全仓唯一一份），
 * 文件不在就直接失败并说清楚该跑哪条命令。
 */
public final class JobStoreTestSupport {

    /** 每个测试一个独立库名，避免用例之间互相看见对方的数据。 */
    private static final AtomicInteger SEQ = new AtomicInteger();

    private JobStoreTestSupport() {
    }

    public static JdbcClient freshDatabase() {
        DataSource ds = new SimpleDriverDataSource(
                new org.h2.Driver(),
                "jdbc:h2:mem:jobstore" + SEQ.incrementAndGet()
                        + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
                "sa", "");
        runScript(ds, h2Schema());
        return JdbcClient.create(ds);
    }

    /**
     * H2 等价脚本从 <b>classpath</b> 读，不按文件路径找。
     *
     * <p>它由 {@code scripts/gen-test-schema.py} 从 {@code db/job} 重放生成，
     * 与迁移一起住在本模块里 —— 迁移的归属者也该是它的生成物的归属者。
     * 放到别的模块去，那个模块就得知道本模块的目录结构，而它没有理由知道。
     *
     * <p><b>刻意不在别处留副本</b>：副本会腐烂 —— 改了源迁移而忘了同步，
     * 测试照样绿，绿的是一张过时的表。
     */
    private static String h2Schema() {
        String path = "db/job-h2/V1__job_baseline.sql";
        try (java.io.InputStream in = JobStoreTestSupport.class.getClassLoader()
                .getResourceAsStream(path)) {
            if (in == null) {
                throw new IllegalStateException("""
                        classpath 上找不到 %s

                        它是生成物，不是手写的。改完 db/job/V*.sql 后重跑：
                          python3 backend/scripts/gen-test-schema.py \\
                            backend/shop-job-store/src/test/resources/db/job-h2/V1__job_baseline.sql \\
                            backend/shop-job-store/src/main/resources/db/job
                        """.formatted(path));
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("读不了 " + path, e);
        }
    }

    private static void runScript(DataSource ds, String sql) {
        try (Connection c = ds.getConnection(); Statement st = c.createStatement()) {
            for (String stmt : sql.split(";")) {
                if (!stmt.isBlank()) {
                    st.execute(stmt);
                }
            }
        } catch (Exception e) {
            throw new IllegalStateException("建表失败", e);
        }
    }
}
