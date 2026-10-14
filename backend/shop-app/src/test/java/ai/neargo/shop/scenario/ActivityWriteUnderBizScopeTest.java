package ai.neargo.shop.scenario;

import static org.assertj.core.api.Assertions.assertThat;

import ai.neargo.common.data.scope.DataScopeContext;
import ai.neargo.shop.auth.LoginUser;
import ai.neargo.shop.promotion.dto.ActivityVOs.ActivityDraft;
import ai.neargo.shop.promotion.dto.ActivityVOs.ActivityVO;
import ai.neargo.shop.promotion.entity.PmtActivity;
import ai.neargo.shop.promotion.service.ActivityService;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

/**
 * 店主会话的数据域下改活动，<b>要真的写进库</b>。
 *
 * <p>2026-09-27 生产上：B 端「结束活动」接口返回 ENDED，回读还是 RUNNING ——
 * {@code pmt_activity} 登记了数据域，店主会话带着 SELF 域，读用了 executeWithoutScope、写没有，
 * UPDATE 被域条件过滤成 0 行，接口却把内存里的对象当结果返回（见记忆「B 端直查带域表读写皆哑」）。
 *
 * <p>别的活动测试没发现它，是因为它们<b>直接调服务、不带数据域</b>，而且断言的是返回值不是库。
 * 这一组两件事都反过来：带上与真实店主会话相同的域，断言从库里读。
 */
@SpringBootTest
@ActiveProfiles("test")
@DisplayName("店主数据域下改活动：结束与编辑都要落库")
class ActivityWriteUnderBizScopeTest {

    private static final String ENTITY = "M0001";

    @Autowired private ActivityService activityService;
    @Autowired private JdbcTemplate jdbc;

    private final List<String> made = new ArrayList<>();

    @AfterEach
    void drop() {
        for (String no : made) {
            jdbc.update("delete from pmt_activity_goods where activity_no=?", no);
            jdbc.update("delete from pmt_activity_audience where activity_no=?", no);
            jdbc.update("delete from pmt_activity where activity_no=?", no);
        }
        made.clear();
    }

    /** 与 /biz 请求里令牌过滤器设的是同一个域：LoginUser.merchantByUser(...).dataScope() */
    private static <T> T asMerchant(Supplier<T> body) {
        DataScopeContext.set(LoginUser.merchantByUser("U-SCOPE-TEST", "店主").dataScope());
        try {
            return body.get();
        } finally {
            DataScopeContext.clear();
        }
    }

    private ActivityVO create(long start, long end) {
        ActivityVO v = activityService.save(ENTITY, draft(null, 500L, start, end), "TEST");
        made.add(v.activityNo());
        return v;
    }

    private String dbStatus(String no) {
        return jdbc.queryForObject("select status from pmt_activity where activity_no=?", String.class, no);
    }

    @Test
    @DisplayName("★★★ 结束活动：库里是 ENDED —— 此前返回 ENDED、库里还是 RUNNING")
    void endActivityPersistsUnderMerchantScope() {
        long now = System.currentTimeMillis();
        ActivityVO a = create(now - 60_000, now + 86_400_000L);
        activityService.setStatus(ENTITY, a.activityNo(), PmtActivity.RUNNING);

        asMerchant(() -> activityService.setStatus(ENTITY, a.activityNo(), PmtActivity.ENDED));

        assertThat(dbStatus(a.activityNo())).isEqualTo(PmtActivity.ENDED);
    }

    @Test
    @DisplayName("★★★ 编辑活动：改的内容落库 —— 同一处 updateById，同一个坑")
    void editActivityPersistsUnderMerchantScope() {
        long now = System.currentTimeMillis();
        ActivityVO a = create(now + 3_600_000L, now + 86_400_000L);

        asMerchant(() -> activityService.save(ENTITY, draft(a.activityNo(), 800L, now + 3_600_000L,
                now + 86_400_000L), "TEST"));

        Long cut = jdbc.queryForObject("select benefit_amount_minor from pmt_activity where activity_no=?",
                Long.class, a.activityNo());
        assertThat(cut).isEqualTo(800L);
    }

    private static ActivityDraft draft(String no, long cut, long start, long end) {
        return new ActivityDraft(no, "数据域写入测试", null, null,
                PmtActivity.TRIGGER_AMOUNT, 10_000L, null,
                PmtActivity.BENEFIT_CUT, cut, null, null,
                PmtActivity.ONE_OFF, start, end, null, 100, null,
                List.of(), List.of(),
                null, null, null, null, null, null, null);
    }
}
