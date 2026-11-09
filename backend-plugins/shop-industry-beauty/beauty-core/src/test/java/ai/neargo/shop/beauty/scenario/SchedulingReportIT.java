package ai.neargo.shop.beauty.scenario;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ai.neargo.shop.auth.LoginUser;
import ai.neargo.shop.beauty.dto.SchedulingReportQuery;
import ai.neargo.shop.beauty.dto.SchedulingReportVO;
import ai.neargo.shop.beauty.mapper.BeautyMappers;
import ai.neargo.shop.beauty.policy.AppointmentAccess;
import ai.neargo.shop.beauty.service.impl.SchedulingReportServiceImpl;
import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.spi.user.MerchantOperatorAccessPort;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * 服务业报表首期（TDD-服务业报表，BAPI-119 子集）：按 {@code start_at} 落在窗口内的预约计数。
 *
 * <p>只需要预约四张表（V1–V7），库迁到 V9 即可（{@link BeautySchemaMigrationIT#baseNine()}）。
 */
class SchedulingReportIT {
    private static final OffsetDateTime FROM = OffsetDateTime.parse("2026-11-09T00:00+08:00");
    private static final OffsetDateTime TO = OffsetDateTime.parse("2026-11-10T00:00+08:00");
    /** 窗口内的一个 UTC 时刻（台北 11-09 10:00） */
    private static final LocalDateTime IN = LocalDateTime.of(2026, 11, 9, 2, 0);

    private final BeautySchemaMigrationIT database = new BeautySchemaMigrationIT();
    private final AtomicBoolean allowed = new AtomicBoolean(true);
    private JdbcTemplate sql;
    private int seq;

    @BeforeEach
    void database() throws Exception {
        database.isolatedDatabase();
        database.baseNine();
        sql = new JdbcTemplate(database.source);
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                LoginUser.merchant("ACTOR", "operator"), null, List.of()));
    }

    @AfterEach
    void logout() {
        SecurityContextHolder.clearContext();
    }

    /** 只注册本测试用到的映射器：持久化配置会拒绝停在 V9 的库，这里不需要它的就绪检查 */
    private BeautyMappers.AppointmentMapper mapper() throws Exception {
        var configuration = new MybatisConfiguration();
        configuration.setMapUnderscoreToCamelCase(true);
        configuration.addMapper(BeautyMappers.AppointmentMapper.class);
        var factory = new MybatisSqlSessionFactoryBean();
        factory.setDataSource(database.source);
        factory.setConfiguration(configuration);
        return new SqlSessionTemplate(factory.getObject()).getMapper(BeautyMappers.AppointmentMapper.class);
    }

    private SchedulingReportVO report(OffsetDateTime from, OffsetDateTime to) throws Exception {
        var factory = new StaticListableBeanFactory(Map.of("access",
                (MerchantOperatorAccessPort) q -> allowed.get() && "E".equals(q.entityNo())
                        && "S".equals(q.storeNo())));
        var service = new SchedulingReportServiceImpl(mapper(),
                new AppointmentAccess(factory.getBeanProvider(MerchantOperatorAccessPort.class), null));
        return service.scheduling("E", "S", "ACTOR", new SchedulingReportQuery(from, to));
    }

    private String appointment(String store, String status, LocalDateTime start, int revision) {
        String no = "BAP" + (++seq);
        sql.update("INSERT INTO bty_appointment (tenant_no, entity_no, created_at, updated_at, appointment_no, store_no,"
                + " source, status, start_at, end_at, timezone, policy_version_no) VALUES ('MAIN','E',?,?,?,?,"
                + "'STAFF',?,?,?,'Asia/Taipei','PV')", IN, IN, no, store, status, start, start.plusHours(1));
        sql.update("INSERT INTO bty_appointment_current (tenant_no, entity_no, created_at, updated_at, appointment_no,"
                + " arrangement_revision) VALUES ('MAIN','E',?,?,?,?)", IN, IN, no, revision);
        return no;
    }

    private String item(String appointment, String sku) {
        String no = "BAI" + (++seq);
        sql.update("INSERT INTO bty_appointment_item (tenant_no, entity_no, created_at, updated_at, appointment_item_no,"
                + " appointment_no, sku_no, definition_version_no, qty, uom, sequence_no, execution_snapshot)"
                + " VALUES ('MAIN','E',?,?,?,?,?,'DV',1,'COUNT',0,'{}')", IN, IN, no, appointment, sku);
        return no;
    }

    private void assign(String item, int revision, String personnel) {
        String no = "BAS" + (++seq);
        sql.update("INSERT INTO bty_appointment_assignment (tenant_no, entity_no, created_at, updated_at,"
                + " planned_assignment_no, appointment_item_no, appointment_revision, requirement_no, resource_no,"
                + " hold_no, start_at, end_at, skill_snapshot, personnel_no) VALUES ('MAIN','E',?,?,?,?,?,'REQ',?,?,"
                + "?,?,'{}',?)", IN, IN, no, item, revision, "R" + seq, "H" + seq, IN, IN.plusHours(1), personnel);
    }

    @Test
    @DisplayName("★★★ AC1–AC3 计数口径：占位不算、到店率分母扣掉取消、技师按当前安排计不重复项目、热门项目取前 5")
    void countsFollowTheAgreedDefinitions() throws Exception {
        String a1 = appointment("S", "CHECKED_IN", IN, 1);
        String i1 = item(a1, "FACIAL");
        assign(i1, 1, "P1");
        assign(i1, 1, "P1");
        assign(item(a1, "MASSAGE"), 1, "P2");
        String a2 = appointment("S", "CHECKED_IN", IN.plusHours(1), 2);
        String i3 = item(a2, "FACIAL");
        assign(i3, 1, "P2");
        assign(i3, 2, "P1");
        String a3 = appointment("S", "CONFIRMED", IN, 1);
        assign(item(a3, "FACIAL"), 1, "P1");
        appointment("S", "NO_SHOW", IN, 1);
        appointment("S", "CANCELLED", IN, 1);
        appointment("S", "HELD", IN, 1);
        item(appointment("S", "CHECKED_IN", IN.plusDays(1), 1), "OUTSIDE");
        item(appointment("OTHER", "CHECKED_IN", IN, 1), "OTHER_STORE");
        String a9 = appointment("S", "CHECKED_IN", IN, 1);
        for (String sku : List.of("S5", "S4", "S3", "S2", "S1")) item(a9, sku);

        SchedulingReportVO r = report(FROM, TO);
        assertThat(r.appointments()).isEqualTo(6);
        assertThat(r.arrivals()).isEqualTo(3);
        assertThat(r.noShows()).isEqualTo(1);
        assertThat(r.cancellations()).isEqualTo(1);
        assertThat(r.arrivalRateBp()).isEqualTo(6000);
        assertThat(r.byPersonnel()).containsExactly(
                new SchedulingReportVO.PersonnelCount("P1", 2), new SchedulingReportVO.PersonnelCount("P2", 1));
        assertThat(r.topServices()).containsExactly(
                new SchedulingReportVO.ServiceCount("FACIAL", 2), new SchedulingReportVO.ServiceCount("MASSAGE", 1),
                new SchedulingReportVO.ServiceCount("S1", 1), new SchedulingReportVO.ServiceCount("S2", 1),
                new SchedulingReportVO.ServiceCount("S3", 1));
    }

    @Test
    @DisplayName("★★ 空窗口：计数为 0，到店率为空（分母为 0 不报 0%）")
    void emptyWindowHasNoRate() throws Exception {
        appointment("S", "CANCELLED", IN, 1);
        SchedulingReportVO r = report(FROM, TO);
        assertThat(r.appointments()).isEqualTo(1);
        assertThat(r.arrivalRateBp()).isNull();
        assertThat(r.byPersonnel()).isEmpty();
        assertThat(r.topServices()).isEmpty();
    }

    @Test
    @DisplayName("★★ AC4/AC5 窗口必填、左闭右开且不超过 93 天；没有预约查看权限 403")
    void windowAndPermissionAreChecked() {
        assertThatThrownBy(() -> report(null, TO)).isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).errorCode()).isEqualTo(ErrorCode.BAD_REQUEST);
        assertThatThrownBy(() -> report(TO, FROM)).isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).errorCode()).isEqualTo(ErrorCode.BAD_REQUEST);
        assertThatThrownBy(() -> report(FROM, FROM.plusDays(94))).isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).errorCode()).isEqualTo(ErrorCode.BAD_REQUEST);
        allowed.set(false);
        assertThatThrownBy(() -> report(FROM, TO)).isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).errorCode()).isEqualTo(ErrorCode.FORBIDDEN);
    }
}
