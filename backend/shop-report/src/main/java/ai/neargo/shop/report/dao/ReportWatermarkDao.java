package ai.neargo.shop.report.dao;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Optional;

import ai.neargo.shop.report.dto.Watermark;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * {@code rpt_job_watermark} 的读写。
 *
 * <p>它只有两个用途，都不是「好看」：
 * <ol>
 *   <li>读侧判断「这一天到底有没有算过」—— 没有水位的话，
 *       「那天没单」与「那天没跑批」在报表上长得一模一样；</li>
 *   <li>运维看日结有没有落后。</li>
 * </ol>
 */
public class ReportWatermarkDao {

    private final JdbcClient jdbc;

    public ReportWatermarkDao(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<Watermark> find(String jobKey) {
        return jdbc.sql("""
                SELECT job_key, last_stat_date, last_run_at, rows_written, duration_ms
                  FROM rpt_job_watermark WHERE job_key = ?
                """).param(jobKey).query(ReportWatermarkDao::map).optional();
    }

    /** 跑完一次就前进一次。**跑失败不要调它** —— 水位的含义是「算到这儿了」。 */
    public void advance(String jobKey, LocalDate lastStatDate, int rowsWritten, long durationMs) {
        int updated = jdbc.sql("""
                UPDATE rpt_job_watermark
                   SET last_stat_date = ?, last_run_at = ?, rows_written = ?, duration_ms = ?
                 WHERE job_key = ?
                """).param(lastStatDate).param(LocalDateTime.now())
                .param(rowsWritten).param(durationMs).param(jobKey).update();
        if (updated == 0) {
            jdbc.sql("""
                    INSERT INTO rpt_job_watermark
                      (job_key, last_stat_date, last_run_at, rows_written, duration_ms)
                    VALUES (?,?,?,?,?)
                    """).param(jobKey).param(lastStatDate).param(LocalDateTime.now())
                    .param(rowsWritten).param(durationMs).update();
        }
    }

    private static Watermark map(ResultSet rs, int rowNum) throws SQLException {
        java.sql.Date d = rs.getDate("last_stat_date");
        java.sql.Timestamp t = rs.getTimestamp("last_run_at");
        return new Watermark(
                rs.getString("job_key"),
                d == null ? null : d.toLocalDate(),
                t == null ? null : t.toLocalDateTime(),
                rs.getInt("rows_written"),
                rs.getLong("duration_ms"));
    }
}
