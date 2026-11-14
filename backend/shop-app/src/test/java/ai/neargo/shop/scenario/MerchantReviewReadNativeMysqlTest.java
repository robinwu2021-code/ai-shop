package ai.neargo.shop.scenario;

import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.AfterAll;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.core.io.ClassPathResource;
import java.nio.charset.StandardCharsets;
import java.util.regex.Pattern;
import java.util.UUID;
import java.util.List;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
import com.baomidou.mybatisplus.core.config.GlobalConfig;
import ai.neargo.shop.common.AuditMetaObjectHandler;
import ai.neargo.shop.product.review.mapper.ReviewMappers.ReviewMapper;
import ai.neargo.shop.product.review.mapper.ReviewMappers.AppealMapper;
import ai.neargo.shop.product.review.mapper.ReviewMappers.ReviewLikeMapper;
import org.mybatis.spring.SqlSessionTemplate;
import ai.neargo.shop.product.review.impl.ReviewServiceImpl;
import tools.jackson.databind.ObjectMapper;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.OptimisticLockerInnerInterceptor;
import ai.neargo.shop.auth.BizContext;
import ai.neargo.shop.common.BizException;
import ai.neargo.shop.product.api.biz.BizReviewController;
import ai.neargo.shop.product.review.ReviewService;
import ai.neargo.shop.product.review.dto.ReviewVO;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@EnabledIfEnvironmentVariable(named = "C06_REVIEW_MYSQL_URL", matches = "jdbc:mysql://127\\.0\\.0\\.1:3308/.*")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class MerchantReviewReadNativeMysqlTest {
    private ReviewService service;
    private JdbcTemplate jdbc, admin;
    private final String schema = "retail_c06_review_" + UUID.randomUUID().toString().replace("-", "");

    @BeforeAll void database() throws Exception {
        String url = System.getenv("C06_REVIEW_MYSQL_URL");
        String user = System.getenv("C06_REVIEW_MYSQL_USER");
        String password = System.getenv("C06_REVIEW_MYSQL_PASSWORD");
        assertThat(url).startsWith("jdbc:mysql://127.0.0.1:3308/information_schema");
        admin = new JdbcTemplate(new DriverManagerDataSource(url, user, password));
        assertThat(admin.queryForObject("SELECT VERSION()", String.class)).startsWith("9.");
        admin.execute("CREATE DATABASE " + schema + " CHARACTER SET utf8mb4 COLLATE utf8mb4_bin");
        var source = new DriverManagerDataSource(url.replace("information_schema", schema), user, password);
        jdbc = new JdbcTemplate(source);
        String ddl = new ClassPathResource("schema-test.sql")
                .getContentAsString(StandardCharsets.UTF_8);
        for (String table : List.of("rvw_review", "rvw_appeal", "rvw_review_like")) {
            var match = Pattern.compile("(?s)CREATE TABLE IF NOT EXISTS " + table + "\\s*\\(.*?\n\\);").matcher(ddl);
            assertThat(match.find()).as(table).isTrue();
            jdbc.execute(match.group());
        }
        var config = new MybatisConfiguration();
        config.setMapUnderscoreToCamelCase(true);
        var factory = new MybatisSqlSessionFactoryBean();
        factory.setDataSource(source); factory.setConfiguration(config);
        var interceptors = new MybatisPlusInterceptor();
        interceptors.addInnerInterceptor(new OptimisticLockerInnerInterceptor());
        factory.setPlugins(interceptors);
        factory.setGlobalConfig(new GlobalConfig()
                .setMetaObjectHandler(new AuditMetaObjectHandler()));
        var sessions = factory.getObject();
        sessions.getConfiguration().addMapper(ReviewMapper.class);
        sessions.getConfiguration().addMapper(AppealMapper.class);
        sessions.getConfiguration().addMapper(ReviewLikeMapper.class);
        var template = new SqlSessionTemplate(sessions);
        service = new ReviewServiceImpl(
                template.getMapper(ReviewMapper.class),
                template.getMapper(ReviewLikeMapper.class),
                template.getMapper(AppealMapper.class),
                null, null, null, null, null, null, new ObjectMapper());
    }
    @AfterAll void dropDatabase() {
        if (admin != null) admin.execute("DROP DATABASE IF EXISTS " + schema);
    }

    @AfterEach void clear() { BizContext.clear(); }

    private void review(String no, String owner, String status) {
        jdbc.update("""
                INSERT INTO rvw_review(review_no,sub_order_no,order_no,goods_no,entity_no,user_no,
                  rating,score_goods,score_fulfillment,score_service,content,status,reply,replied_at,
                  created_at,updated_at)
                VALUES(?,?,?,?,?,?,2,2,1,3,'content',?,'original reply',123456,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)
                """, no, "OS-" + no, "SO-" + no, "G-" + no, owner, "U", status);
    }
    private void appeal(String no, String owner, String status) {
        jdbc.update("""
                INSERT INTO rvw_appeal(appeal_no,review_no,entity_no,reason,status,submitted_at,verdict,
                  created_at,updated_at)
                VALUES(?,?,?,'reason',?,123456,'decision',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)
                """, "AP-" + no, no, owner, status);
    }

    @Test void hiddenReviewCanBeReadOnlyByOriginalMerchant() {
        review("RV-C06-H", "M-C06", "REJECTED");
        appeal("RV-C06-H", "M-C06", "UPHELD");
        ReviewVO r = service.merchantReview("M-C06", "RV-C06-H");
        assertThat(r.appeal().appealNo()).isEqualTo("AP-RV-C06-H");
        assertThat(r.appeal().status()).isEqualTo("UPHELD");
        assertThat(r.repliedAt()).isEqualTo(123456);
        assertThat(r.scores().fulfillment()).isEqualTo(1);
        assertThat(service.list(null, "M-C06")).isEmpty();
        assertThatThrownBy(() -> service.merchantReview("M-OTHER", "RV-C06-H"))
                .isInstanceOf(BizException.class);
    }

    @Test void appealsArePagedScopedAndKeepHiddenVerdicts() {
        for (int i = 0; i < 52; i++) {
            String no = "RV-C06-P" + i;
            review(no, "M-C06-P", i % 2 == 0 ? "PASSED" : "REJECTED");
            appeal(no, "M-C06-P", i % 2 == 0 ? "PENDING" : "UPHELD");
        }
        review("RV-C06-OTHER", "M-OTHER", "REJECTED");
        appeal("RV-C06-OTHER", "M-OTHER", "UPHELD");
        var first = service.merchantAppeals("M-C06-P", 1, 100);
        var second = service.merchantAppeals("M-C06-P", 2, 50);
        assertThat(first).hasSize(50).allMatch(r -> r.merchantNo().equals("M-C06-P"));
        assertThat(second).hasSize(2).allMatch(r -> r.merchantNo().equals("M-C06-P"));
        assertThat(first).extracting(ReviewVO::reviewNo).doesNotContainAnyElementsOf(
                second.stream().map(ReviewVO::reviewNo).toList());
        assertThat(first).anyMatch(r -> r.appeal().status().equals("UPHELD")
                && r.appeal().verdict().equals("decision"));
        assertThat(service.merchantAppeals("M-EMPTY", 1, 50)).isEmpty();
    }

    @Test void bizRoutesUseContextAndExposeRealScoresAndPagination() throws Exception {
        for (int i = 0; i < 51; i++) review("RV-C06-L" + i, "M-C06-L", "PASSED");
        appeal("RV-C06-L0", "M-C06-L", "REJECTED");
        BizContext.set(new BizContext("M-C06-L", Set.of(), Set.of(), Set.of(), "S", true, Map.of()));
        var mvc = MockMvcBuilders.standaloneSetup(new BizReviewController(service)).build();
        mvc.perform(get("/biz/review").param("page", "2").param("size", "50"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].reviewNo").value("RV-C06-L0"));
        mvc.perform(get("/biz/review/RV-C06-L0"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.scores.goods").value(2))
                .andExpect(jsonPath("$.appeal.verdict").value("decision"));
        mvc.perform(get("/biz/review/appeals"))
                .andExpect(status().isOk()).andExpect(jsonPath("$[0].reviewNo").value("RV-C06-L0"));
    }
    @Test void replyStillBelongsToOriginalMerchantAndCannotBeRepeated() {
        review("RV-C06-RULE-R", "M-C06-RULE", "PASSED");
        jdbc.update("UPDATE rvw_review SET reply=NULL,replied_at=NULL WHERE review_no='RV-C06-RULE-R'");
        assertThatThrownBy(() -> service.reply("M-OTHER", "RV-C06-RULE-R", "wrong"))
                .isInstanceOf(BizException.class);
        assertThat(service.reply("M-C06-RULE", "RV-C06-RULE-R", " first reply ").reply()).isEqualTo("first reply");
        assertThatThrownBy(() -> service.reply("M-C06-RULE", "RV-C06-RULE-R", "second"))
                .isInstanceOf(BizException.class);
        assertThat(service.merchantReview("M-C06-RULE", "RV-C06-RULE-R").reply()).isEqualTo("first reply");
    }

    @Test void lowStarAppealRemainsOnceOnlyAndRejectsHighStars() {
        review("RV-C06-RULE-A", "M-C06-RULE", "PASSED");
        assertThatThrownBy(() -> service.appeal("M-OTHER", "RV-C06-RULE-A", "reason", List.of()))
                .isInstanceOf(BizException.class);
        var first = service.appeal("M-C06-RULE", "RV-C06-RULE-A", " original reason ", List.of("image"));
        assertThat(first.appeal().reason()).isEqualTo("original reason");
        assertThatThrownBy(() -> service.appeal("M-C06-RULE", "RV-C06-RULE-A", "second", List.of()))
                .isInstanceOf(BizException.class);
        review("RV-C06-RULE-GOOD", "M-C06-RULE", "PASSED");
        jdbc.update("UPDATE rvw_review SET rating=5 WHERE review_no='RV-C06-RULE-GOOD'");
        assertThatThrownBy(() -> service.appeal("M-C06-RULE", "RV-C06-RULE-GOOD", "reason", List.of()))
                .isInstanceOf(BizException.class);
    }

}
