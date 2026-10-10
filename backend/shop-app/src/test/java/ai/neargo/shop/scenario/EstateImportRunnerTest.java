package ai.neargo.shop.scenario;

import ai.neargo.shop.config.EstateImportRunner;
import ai.neargo.shop.community.service.CommunityAdminService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import tools.jackson.databind.ObjectMapper;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 跟着部署跑的一次性导入器。
 *
 * <p>这一组守的是「**别把未知当成空**」与「**别在启动时开城**」——
 * 两件事失手都不报错，而后果分别是「一份看起来很满的缺失数据」
 * 与「没人在看屏幕的时候批量开城」。
 */
@SpringBootTest
@ActiveProfiles("test")
class EstateImportRunnerTest {

    private static final String REGION = "998803";
    private static final String POI = "RUNNERPOI01";

    @Autowired
    private CommunityAdminService admin;
    @Autowired
    private ObjectMapper json;
    @Autowired
    private JdbcTemplate jdbc;

    @AfterEach
    void clean() {
        jdbc.update("DELETE FROM cmt_community WHERE origin_code = ?", POI);
    }

    private Path write(String body) throws Exception {
        Path p = Files.createTempFile("estates-", ".json");
        Files.writeString(p, body);
        p.toFile().deleteOnExit();
        return p;
    }

    private static final String ONE_ITEM =
            "{\"poiId\":\"" + POI + "\",\"name\":\"跑批小区\",\"address\":\"某路 1 号\","
                    + "\"latE6\":22600000,\"lngE6\":114000000}";

    private int rows() {
        return jdbc.queryForObject(
                "SELECT count(*) FROM cmt_community WHERE origin_code = ?", Integer.class, POI);
    }

    @Test
    @DisplayName("★★★ 正常一份：导进来，而且**一律 CLOSED**（启动期不许开城）")
    void importsAsClosed() throws Exception {
        Path f = write("{\"adcode\":\"" + REGION + "\",\"capped\":0,\"failed\":[],"
                + "\"items\":[" + ONE_ITEM + "]}");
        new EstateImportRunner(admin, json, f.toString()).run(null);

        assertThat(rows()).as("对照量：正常那份要真的导进来，否则下面几条什么也没证明").isEqualTo(1);
        assertThat(jdbc.queryForObject(
                "SELECT status FROM cmt_community WHERE origin_code = ?", String.class, POI))
                .as("启动时就开城 = 没人在看屏幕的时候批量改变了买家看到的东西")
                .isEqualTo("CLOSED");
    }

    @Test
    @DisplayName("★★★ 有格子扫失败的那份**一条都不导** —— 那片是「未知」不是「空」")
    void refusesFilesWithFailedCells() throws Exception {
        Path f = write("{\"adcode\":\"" + REGION + "\",\"capped\":0,"
                + "\"failed\":[{\"lng\":114.0,\"lat\":22.6,\"why\":\"限流\"}],"
                + "\"items\":[" + ONE_ITEM + "]}");
        new EstateImportRunner(admin, json, f.toString()).run(null);
        /*
         * 被限流吞掉的格子在高德的返回上与「这儿没有小区」一模一样
         * （count=0 + info=OK）。导进来就是一份看起来很满、实际系统性缺失的表，
         * 而缺失的那些小区里的买家会落进别人家的围栏 —— 且没有任何报错。
         */
        assertThat(rows()).as("有失败格子还照导 = 把未知当成了空").isZero();
    }

    @Test
    @DisplayName("★★ 文件坏了不拖垮启动 —— 补数据的动作不该让线上起不来")
    void brokenFileDoesNotThrow() throws Exception {
        Path f = write("{ 这不是 JSON");
        new EstateImportRunner(admin, json, f.toString()).run(null);
        assertThat(rows()).isZero();

        new EstateImportRunner(admin, json, "/tmp/根本不存在的文件-" + System.nanoTime()).run(null);
        assertThat(rows()).isZero();
    }

    @Test
    @DisplayName("★★ 重跑不重复 —— 部署会跑很多次，而配置可能一直留着")
    void rerunIsIdempotent() throws Exception {
        Path f = write("{\"adcode\":\"" + REGION + "\",\"capped\":0,\"failed\":[],"
                + "\"items\":[" + ONE_ITEM + "]}");
        new EstateImportRunner(admin, json, f.toString()).run(null);
        new EstateImportRunner(admin, json, f.toString()).run(null);
        new EstateImportRunner(admin, json, f.toString()).run(null);
        assertThat(rows()).as("每次部署多一条 = 部署三次就是三份重复的小区").isEqualTo(1);
    }
}
