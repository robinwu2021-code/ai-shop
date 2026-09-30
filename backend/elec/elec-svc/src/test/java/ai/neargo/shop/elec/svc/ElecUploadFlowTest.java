package ai.neargo.shop.elec.svc;

import ai.neargo.shop.elec.config.ElecProperties;
import ai.neargo.shop.elec.entity.ElcStock;
import ai.neargo.shop.elec.entity.ElcStockBatch;
import ai.neargo.shop.elec.entity.ElcStockBatchRow;
import ai.neargo.shop.elec.gateway.ElecColumnAi;
import ai.neargo.shop.elec.mapper.ElecMappers.StockBatchMapper;
import ai.neargo.shop.elec.mapper.ElecMappers.StockBatchRowMapper;
import ai.neargo.shop.elec.mapper.ElecMappers.StockMapper;
import ai.neargo.shop.elec.service.impl.PendingBatchCache;
import ai.neargo.shop.elec.service.impl.UploadHousekeeper;
import ai.neargo.shop.elec.support.SheetReader;
import ai.neargo.shop.elec.support.UploadFileStore;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * 库存上传二期的整条链路：认列（别名 / 大模型 / 手工）、定位到格、内存暂存与重建、原件两区、护栏、限次、记录。
 * 对账表见 docs/technical/TDD-元器件-库存上传二期.md §5 —— 方法名前的 acN 就是那张表的行。
 *
 * <p>大模型是替身（{@link FakeColumnAi}）：回什么由用例定，并数调用次数。每个用例用自己的手机号与料号前缀。
 */
@SpringBootTest(classes = ElecApplication.class)
@ActiveProfiles("test")
@Import({FakeMainSystem.Config.class, FakeColumnAi.Config.class})
class ElecUploadFlowTest {

    private static final AtomicInteger SEQ = new AtomicInteger();

    @Autowired
    private WebApplicationContext context;
    @Autowired
    private ObjectMapper json;
    @Autowired
    private FakeMainSystem main;
    @Autowired
    private FakeColumnAi ai;
    @Autowired
    private StockMapper stockMapper;
    @Autowired
    private StockBatchMapper batchMapper;
    @Autowired
    private StockBatchRowMapper rowMapper;
    @Autowired
    private PendingBatchCache cache;
    @Autowired
    private UploadFileStore files;
    @Autowired
    private ElecProperties props;

    @TempDir
    Path tmp;

    @BeforeEach
    void resetAi() {
        ai.calls.set(0);
        ai.answer = head -> null;
    }

    @AfterEach
    void restoreProps() {
        props.getUpload().setDailyMax(20);
    }

    private MockMvc mvc() {
        return MockMvcBuilders.webAppContextSetup(context)
                .apply(org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity())
                .build();
    }

    /** 实测过的那种表头：Item / Maker / Stk 都不在别名表里 */
    private static String itemMakerStk(String p) {
        return "序,Item,Maker,Stk,年份\n"
                + "1," + p + "A1,TI,2500,2338\n"
                + "2," + p + "A2,ST,10K,23+\n";
    }

    private static ElecColumnAi.Guess itemMakerStkGuess() {
        return new ElecColumnAi.Guess(0, Map.of("MPN", 1, "MFR", 2, "QTY", 3));
    }

    // ── 认列 ────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("ac2 ac5 ★★★ 别名认不出交给大模型（来源 AI）；确认后学成这家的别名，下次同写法不再问；别家不受影响")
    void ac5_confirmedMappingLearnedForThisSupplierOnly() throws Exception {
        String p = prefix();
        String a = supplier("12600980001", "学习甲电子");
        ai.answer = head -> itemMakerStkGuess();
        JsonNode pv = upload(a, "a.csv", itemMakerStk(p), "MERGE");
        assertThat(pv.get("status").asString()).isEqualTo("PARSED");
        assertThat(pv.get("columnSource").get("MPN").asString()).isEqualTo("AI");
        assertThat(pv.get("columnSource").get("DC").asString()).as("年份是别名认的").isEqualTo("ALIAS");
        apply(a, pv, null);
        assertThat(ai.calls.get()).isEqualTo(1);

        // 同一家、表头多一列（「记住的映射」要一字不差，这里故意不中）：学到的别名直接命中
        ai.answer = head -> null;
        JsonNode again = upload(a, "a2.csv", "序,Item,Maker,Stk,年份,备注\n1," + p + "A3,TI,100,2338,\n", "MERGE");
        assertThat(ai.calls.get()).as("学到了，不再问大模型").isEqualTo(1);
        assertThat(again.get("status").asString()).isEqualTo("PARSED");
        assertThat(again.get("columnSource").get("MPN").asString()).isEqualTo("ALIAS");

        // 别家：没学过，还得问（这次模型不在 → 待指定列）
        String b = supplier("12600980002", "学习乙电子");
        JsonNode other = upload(b, "b.csv", itemMakerStk(prefix()), "MERGE");
        assertThat(ai.calls.get()).isEqualTo(2);
        assertThat(other.get("status").asString()).isEqualTo("NEED_MAPPING");
    }

    @Test
    @DisplayName("ac4 ★★★ 大模型不可用：不报错，回待指定列；手工选完照常上架，手工选的也学成本家别名")
    void ac4_aiDownGivesNeedMapping() throws Exception {
        String p = prefix();
        String user = supplier("12600980003", "手工电子");
        JsonNode pv = upload(user, "m.csv", itemMakerStk(p), "MERGE");
        assertThat(pv.get("status").asString()).isEqualTo("NEED_MAPPING");
        assertThat(pv.get("headers").toString()).contains("Item").contains("Stk");
        JsonNode mapped = data(post("/elec/b/stock/batch/" + no(pv) + "/remap"), user,
                "{\"columns\":{\"MPN\":1,\"MFR\":2,\"QTY\":3,\"DC\":4}}");
        assertThat(mapped.get("status").asString()).isEqualTo("PARSED");
        assertThat(mapped.get("columnSource").get("MPN").asString()).isEqualTo("MANUAL");
        assertThat(mapped.get("rowValid").asInt()).isEqualTo(2);
        apply(user, mapped, null);
        assertThat(countStock(p)).isEqualTo(2);

        JsonNode again = upload(user, "m2.csv", "Item,Stk\n" + p + "A9,5\n", "MERGE");
        assertThat(again.get("status").asString()).as("手工选的学进去了").isEqualTo("PARSED");
    }

    // ── 报错 ────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("ac6 ac7 ★★★ 问题定位到格（行、列、表头、原值），一行多处全列；警告行照常上架")
    void ac6_issuesPinpointCell() throws Exception {
        String p = prefix();
        String user = supplier("12600980004", "定位电子");
        JsonNode pv = upload(user, "i.csv", "型号,品牌,数量\n" + p + "A1,TI,100\n,TI,约2千\n" + p + "A3,TIX,5\n",
                "MERGE");
        assertThat(pv.get("rowInvalid").asInt()).isEqualTo(1);
        assertThat(pv.get("rowWarn").asInt()).isEqualTo(1);
        JsonNode issues = pv.get("issues");
        assertThat(issues.size()).isEqualTo(3);
        JsonNode qty = issues.get(1);
        assertThat(qty.get("row").asInt()).isEqualTo(3);
        assertThat(qty.get("col").asInt()).isEqualTo(2);
        assertThat(qty.get("header").asString()).isEqualTo("数量");
        assertThat(qty.get("value").asString()).isEqualTo("约2千");
        assertThat(qty.get("code").asString()).isEqualTo("QTY_INVALID");
        assertThat(issues.get(2).get("level").asString()).isEqualTo("WARN");
        assertThat(pv.get("problems").size()).as("过渡字段：老端上只看到错误，一行一条").isEqualTo(1);
        apply(user, pv, null);
        assertThat(countStock(p)).as("警告行在售，错误行不在").isEqualTo(2);
    }

    @Test
    @DisplayName("ac8 ac9 ★★★ 导出问题行：xlsx、只有问题行、原表头加原行号与问题；改好后按合并补传，已上架的不动")
    void ac8_exportThenMergeFix() throws Exception {
        String p = prefix();
        String user = supplier("12600980005", "导出电子");
        JsonNode pv = upload(user, "库存 9月.csv", "型号,数量\n" + p + "A1,100\n" + p + "0805,abc\n", "MERGE");
        MockHttpServletResponse res = raw(get("/elec/b/stock/batch/" + no(pv) + "/problems"), user);
        assertThat(res.getContentType()).startsWith("application/vnd.openxmlformats");
        assertThat(res.getHeader("Content-Disposition")).contains("filename*=UTF-8''");
        byte[] x = res.getContentAsByteArray();
        assertThat(new String(x, 0, 2, StandardCharsets.ISO_8859_1)).isEqualTo("PK");
        List<List<String>> rows = SheetReader.read(x, 100);
        assertThat(rows.get(0)).containsExactly("型号", "数量", "原行号", "问题");
        assertThat(rows).hasSize(2);
        assertThat(rows.get(1).get(0)).isEqualTo(p + "0805");
        assertThat(rows.get(1).get(2)).isEqualTo("3");
        assertThat(rows.get(1).get(3)).contains("B3").contains("数量读不出").contains("abc");
        apply(user, pv, null);

        // 改好那一行，按合并补传
        JsonNode fix = upload(user, "fix.csv", "型号,数量\n" + p + "0805,300\n", "MERGE");
        assertThat(fix.get("toInsert").asInt()).isEqualTo(1);
        apply(user, fix, null);
        assertThat(countStock(p)).as("原来那行还在，补传的也上了").isEqualTo(2);
    }

    // ── 预览与暂存 ──────────────────────────────────────────────────────────

    @Test
    @DisplayName("ac10 ac11 ★★★ 预览按类看解析后的行（更新带旧值）；确认之前库里只有批次，没有任何数据行")
    void ac10_rowsViewAndNothingInDbBeforeApply() throws Exception {
        String p = prefix();
        String user = supplier("12600980006", "分类电子");
        applyCsv(user, "型号,数量\n" + p + "U1,100\n" + p + "D1,50\n" + p + "S1,7\n");
        JsonNode pv = upload(user, "r.csv", "型号,数量\n" + p + "U1,300\n" + p + "S1,7\n" + p + "N1,9\n电阻,1\n",
                "REPLACE");
        assertThat(rowCount(no(pv))).as("预览阶段一条数据行都不落库").isZero();

        JsonNode upd = rows(user, pv, "UPDATE");
        assertThat(upd.size()).isEqualTo(1);
        assertThat(upd.get(0).get("qty").asLong()).isEqualTo(300L);
        assertThat(upd.get(0).get("before").get("qty").asLong()).isEqualTo(100L);
        assertThat(rows(user, pv, "INSERT").get(0).get("mpn").asString()).isEqualTo(p + "N1");
        assertThat(rows(user, pv, "UNCHANGED").get(0).get("mpn").asString()).isEqualTo(p + "S1");
        assertThat(rows(user, pv, "DELIST").get(0).get("mpn").asString()).isEqualTo(p + "D1");
        assertThat(rows(user, pv, "PROBLEM").get(0).get("issues").get(0).get("code").asString())
                .isEqualTo("MPN_INVALID");

        apply(user, pv, pv.get("toDelist").asInt());
        assertThat(rowCount(no(pv))).as("确认后只落有问题的行").isEqualTo(1);
    }

    @Test
    @DisplayName("ac12 ★★★ 放弃：状态已放弃、内存清掉、原件留在未入库区；再确认回「已失效」")
    void ac12_cancelEvictsButKeepsFile() throws Exception {
        String user = supplier("12600980007", "放弃电子");
        JsonNode pv = upload(user, "c.csv", "型号,数量\n" + prefix() + "C1,1\n", "MERGE");
        JsonNode c = data(delete("/elec/b/stock/batch/" + no(pv)), user, null);
        assertThat(c.get("status").asString()).isEqualTo("CANCELLED");
        assertThat(cache(no(pv))).isNull();
        ElcStockBatch b = batch(no(pv));
        assertThat(Files.exists(files.root().resolve("failed").resolve(b.getFilePath()))).isTrue();
        assertThat(call(post("/elec/b/stock/batch/" + no(pv) + "/apply"), user, null).get("code").asInt())
                .isEqualTo(90008);
    }

    @Test
    @DisplayName("ac13 ★★★ 上传满 1 小时就失效（以库里的创建时刻为准，缓存里还在也不算数）；记录里显示已过期")
    void ac13_expiresOneHourAfterUpload() throws Exception {
        String user = supplier("12600980008", "过期电子");
        JsonNode pv = upload(user, "e.csv", "型号,数量\n" + prefix() + "E1,1\n", "MERGE");
        assertThat(cache(no(pv))).isNotNull();
        backdate(no(pv), 61);
        assertThat(call(post("/elec/b/stock/batch/" + no(pv) + "/apply"), user, null).get("code").asInt())
                .isEqualTo(90008);
        assertThat(call(get("/elec/b/stock/batch/" + no(pv) + "/rows"), user, null).get("code").asInt())
                .isEqualTo(90008);
        assertThat(data(get("/elec/b/stock/batch/" + no(pv)), user, null).get("status").asString())
                .isEqualTo("EXPIRED");
    }

    @Test
    @DisplayName("ac14 ★★★ 缓存丢了（重启 / 挤出）从原件重建：不重调大模型、映射是他改过的、到期时刻不变")
    void ac14_rebuildFromFileKeepsMappingAndDeadline() throws Exception {
        String p = prefix();
        String user = supplier("12600980009", "重建电子");
        ai.answer = head -> itemMakerStkGuess();
        JsonNode pv = upload(user, "r.csv", itemMakerStk(p), "MERGE");
        // 他把批号那一列改成不导入
        JsonNode mapped = data(post("/elec/b/stock/batch/" + no(pv) + "/remap"), user,
                "{\"columns\":{\"MPN\":1,\"MFR\":2,\"QTY\":3}}");
        String deadline = mapped.get("deadline").asString();
        int callsBefore = ai.calls.get();

        cache.evict(no(pv));
        JsonNode detail = data(get("/elec/b/stock/batch/" + no(pv)), user, null);
        assertThat(detail.get("columns").has("DC")).as("重建用的是他改过的映射").isFalse();
        assertThat(detail.get("deadline").asString()).isEqualTo(deadline);
        assertThat(ai.calls.get()).as("重建不重新认列").isEqualTo(callsBefore);
        assertThat(cache(no(pv))).as("重建完放回缓存").isNotNull();

        cache.evict(no(pv));
        apply(user, pv, null);
        assertThat(countStock(p)).isEqualTo(2);
    }

    @Test
    @DisplayName("ac15 ★★ 一家只留一张待确认：再传一张，前一张作废")
    void ac15_newUploadSupersedesPending() throws Exception {
        String user = supplier("12600980010", "作废电子");
        JsonNode first = upload(user, "1.csv", "型号,数量\n" + prefix() + "F1,1\n", "MERGE");
        upload(user, "2.csv", "型号,数量\n" + prefix() + "F2,1\n", "MERGE");
        assertThat(batch(no(first)).getStatus()).isEqualTo("SUPERSEDED");
        assertThat(cache(no(first))).isNull();
        assertThat(call(post("/elec/b/stock/batch/" + no(first) + "/apply"), user, null).get("code").asInt())
                .isEqualTo(90008);
    }

    // ── 护栏与并发 ──────────────────────────────────────────────────────────

    @Test
    @DisplayName("ac16 ac17 ★★★ 下架过线要带此刻的下架数；预览之后库存变了，带旧数被拒并告知新数")
    void ac16ac17_delistGuardComparesRecomputed() throws Exception {
        String p = prefix();
        String user = supplier("12600980011", "护栏电子");
        StringBuilder all = new StringBuilder("型号,数量\n");
        for (int i = 0; i < 10; i++) {
            all.append(p).append("G").append(i).append(",1\n");
        }
        applyCsv(user, all.toString());
        StringBuilder six = new StringBuilder("型号,数量\n");
        for (int i = 0; i < 6; i++) {
            six.append(p).append("G").append(i).append(",1\n");
        }
        JsonNode pv = upload(user, "half.csv", six.toString(), "REPLACE");
        assertThat(pv.get("toDelist").asInt()).isEqualTo(4);
        assertThat(pv.get("delistConfirm").asBoolean()).as("4/10 过了 30% 的线").isTrue();

        JsonNode noBody = call(post("/elec/b/stock/batch/" + no(pv) + "/apply"), user, null);
        assertThat(noBody.get("code").asInt()).isEqualTo(90016);
        assertThat(noBody.get("msg").asString()).contains("4");
        assertThat(countOn(p)).as("被拒时一行都没动").isEqualTo(10);

        // 预览之后，将下架的一行被别处下架了：此刻只会再下架 3 行
        stockMapper.update(null, Wrappers.<ElcStock>lambdaUpdate().eq(ElcStock::getMpnRaw, p + "G9")
                .set(ElcStock::getStatus, ElcStock.STATUS_DELISTED));
        JsonNode stale = call(post("/elec/b/stock/batch/" + no(pv) + "/apply"), user, "{\"expectDelist\":4}");
        assertThat(stale.get("code").asInt()).isEqualTo(90016);
        assertThat(stale.get("msg").asString()).contains("3");
        apply(user, pv, 3);
        assertThat(countOn(p)).isEqualTo(6);
    }

    @Test
    @DisplayName("ac18 ★★★ 同一张表被连点两次确认：只上架一次（没有唯一键兜底，不锁就插两遍）")
    void ac18_concurrentApplySerialized() throws Exception {
        for (int round = 0; round < 5; round++) {
            String p = prefix();
            String user = supplier("1260098" + String.format("%04d", 100 + round), "并发电子" + round);
            JsonNode pv = upload(user, "x.csv", "型号,数量\n" + p + "Z1,1\n" + p + "Z2,2\n" + p + "Z3,3\n", "MERGE");
            CountDownLatch go = new CountDownLatch(1);
            ExecutorService pool = Executors.newFixedThreadPool(2);
            List<Future<Integer>> fs = new ArrayList<>();
            for (int i = 0; i < 2; i++) {
                fs.add(pool.submit(() -> {
                    go.await();
                    return call(post("/elec/b/stock/batch/" + no(pv) + "/apply"), user, null).get("code").asInt();
                }));
            }
            go.countDown();
            List<Integer> codes = new ArrayList<>();
            for (Future<Integer> f : fs) {
                codes.add(f.get());
            }
            pool.shutdown();
            assertThat(codes).as("第 %d 轮", round).containsExactlyInAnyOrder(0, 90008);
            assertThat(countStock(p)).as("第 %d 轮：库存行只有一份", round).isEqualTo(3);
        }
    }

    // ── 原件、记录与限次 ────────────────────────────────────────────────────

    @Test
    @DisplayName("ac19 ★★★ 原件用端上的原名、在 failed/日期/供应商号/ 下；确认后移到 applied/ 同一相对路径，库里记着")
    void ac19_fileNamedAndMovedOnApply() throws Exception {
        String user = supplier("12600980012", "原件电子");
        JsonNode pv = uploadNamed(user, "tmp_8a3f.csv", "9月库存（华强）.xlsx",
                ("型号,数量\n" + prefix() + "O1,1\n").getBytes(StandardCharsets.UTF_8), "MERGE");
        ElcStockBatch b = batch(no(pv));
        assertThat(b.getFileName()).as("记的是端上选的名字，不是临时路径名").isEqualTo("9月库存（华强）.xlsx");
        assertThat(b.getFilePath()).startsWith(LocalDate.now() + "/" + b.getSupplierNo() + "/9月库存（华强）_" + no(pv))
                .endsWith(".csv");
        assertThat(b.getFileArea()).isEqualTo("FAILED");
        assertThat(b.getFileSha256()).hasSize(64);
        assertThat(Files.exists(files.root().resolve("failed").resolve(b.getFilePath()))).isTrue();

        apply(user, pv, null);
        ElcStockBatch after = batch(no(pv));
        assertThat(after.getFileArea()).isEqualTo("APPLIED");
        assertThat(Files.exists(files.root().resolve("applied").resolve(b.getFilePath()))).isTrue();
        assertThat(Files.exists(files.root().resolve("failed").resolve(b.getFilePath()))).isFalse();
    }

    @Test
    @DisplayName("ac19a ★★★ 解析失败也建批次、记原因、原件留下；端上照样收到原来的报错")
    void ac19a_parseFailureRecordedWithFile() throws Exception {
        String user = supplier("12600980013", "失败电子");
        byte[] ole = {(byte) 0xD0, (byte) 0xCF, 0x11, (byte) 0xE0, (byte) 0xA1, (byte) 0xB1, 0x1A, (byte) 0xE1, 0};
        JsonNode r = uploadRawNamed(user, "old.xls", "老表.xls", ole, "MERGE");
        assertThat(r.get("code").asInt()).isEqualTo(90005);
        ElcStockBatch b = batchMapper.selectOne(Wrappers.<ElcStockBatch>lambdaQuery()
                .eq(ElcStockBatch::getFileName, "老表.xls").orderByDesc(ElcStockBatch::getId).last("LIMIT 1"));
        assertThat(b).as("解析失败的记录没被回滚掉").isNotNull();
        assertThat(b.getStatus()).isEqualTo("FAILED");
        assertThat(b.getFailCode()).isEqualTo("err.elec.upload_format");
        assertThat(b.getFilePath()).endsWith(".xls");
        assertThat(Files.exists(files.root().resolve("failed").resolve(b.getFilePath()))).isTrue();
    }

    @Test
    @DisplayName("ac19b ac19c ★★★ 每周清理：先把「已上架却还在 failed」的补移，再删 failed 里过期的日期目录并记下；applied 不动")
    void ac19bc_housekeeping() throws Exception {
        // 用独立目录与一个不会和别的用例撞的日期：清理会按日期整目录删、按日期前缀标记批次
        UploadFileStore store = new UploadFileStore(tmp);
        store.selfCheck();
        LocalDate old = LocalDate.of(2020, 1, 1);
        String s = "SHK" + SEQ.incrementAndGet();
        String appliedLeftBehind = store.storeFailed(old, s, "EBHK1", "已上架.csv", "a".getBytes()).relPath();
        String cancelled = store.storeFailed(old, s, "EBHK2", "放弃.csv", "b".getBytes()).relPath();
        String appliedOld = store.storeFailed(old, s, "EBHK3", "老的已上架.csv", "c".getBytes()).relPath();
        store.moveToApplied(appliedOld);
        insertBatch("EBHK1-" + s, s, "APPLIED", appliedLeftBehind, "FAILED");
        insertBatch("EBHK2-" + s, s, "CANCELLED", cancelled, "FAILED");
        insertBatch("EBHK3-" + s, s, "APPLIED", appliedOld, "APPLIED");

        UploadHousekeeper.Report r = new UploadHousekeeper(batchMapper, store, props).run(LocalDate.of(2026, 10, 5));
        assertThat(r.rehomed()).isGreaterThanOrEqualTo(1);
        assertThat(Files.exists(tmp.resolve("applied").resolve(appliedLeftBehind))).as("补移过去了，没被删").isTrue();
        assertThat(batch("EBHK1-" + s).getFileArea()).isEqualTo("APPLIED");
        assertThat(Files.exists(tmp.resolve("failed/2020-01-01"))).isFalse();
        assertThat(batch("EBHK2-" + s).getFilePurgedAt()).isNotNull();
        assertThat(Files.exists(tmp.resolve("applied").resolve(appliedOld))).as("已入库区暂不删").isTrue();
        assertThat(batch("EBHK3-" + s).getFilePurgedAt()).isNull();
    }

    @Test
    @DisplayName("ac20 ★★ 每天上传次数有上限，只数上传；改映射、放弃不算")
    void ac20_dailyLimitCountsUploadsOnly() throws Exception {
        props.getUpload().setDailyMax(2);
        String user = supplier("12600980014", "限次电子");
        JsonNode first = upload(user, "1.csv", "Item,Stk\n" + prefix() + "L1,1\n", "MERGE");
        data(post("/elec/b/stock/batch/" + no(first) + "/remap"), user, "{\"columns\":{\"MPN\":0,\"QTY\":1}}");
        data(delete("/elec/b/stock/batch/" + no(first)), user, null);
        upload(user, "2.csv", "型号,数量\n" + prefix() + "L2,1\n", "MERGE");
        JsonNode third = uploadRaw(user, "3.csv", ("型号,数量\n" + prefix() + "L3,1\n").getBytes(StandardCharsets.UTF_8),
                "MERGE");
        assertThat(third.get("code").asInt()).isEqualTo(90015);
        assertThat(third.get("msg").asString()).contains("2");
    }

    @Test
    @DisplayName("ac21 ★★★ 上传记录：五种状态各自正确、显示原名；已上架的原件被删了照样能导出问题行")
    void ac21_historyStatusesAndExportAfterFileCleaned() throws Exception {
        String p = prefix();
        String user = supplier("12600980015", "记录电子");
        JsonNode applied = uploadNamed(user, "t.csv", "一.csv",
                ("型号,数量\n" + p + "H1,1\n" + p + "H2,abc\n").getBytes(StandardCharsets.UTF_8), "MERGE");
        apply(user, applied, null);
        JsonNode cancelled = upload(user, "二.csv", "型号,数量\n" + p + "H3,1\n", "MERGE");
        data(delete("/elec/b/stock/batch/" + no(cancelled)), user, null);
        byte[] ole = {(byte) 0xD0, (byte) 0xCF, 0x11, (byte) 0xE0, (byte) 0xA1, (byte) 0xB1, 0x1A, (byte) 0xE1, 0};
        assertThat(uploadRawNamed(user, "t.xls", "三.xls", ole, "MERGE").get("code").asInt()).isEqualTo(90005);
        JsonNode expired = upload(user, "四.csv", "型号,数量\n" + p + "H4,1\n", "MERGE");
        backdate(no(expired), 90);
        upload(user, "五.csv", "型号,数量\n" + p + "H5,1\n", "MERGE");

        JsonNode list = data(get("/elec/b/stock/batch"), user, null);
        assertThat(list.size()).isEqualTo(5);
        List<String> statuses = new ArrayList<>();
        list.forEach(n -> statuses.add(n.get("fileName").asString() + ":" + n.get("status").asString()));
        assertThat(statuses).containsExactly("五.csv:PARSED", "四.csv:EXPIRED", "三.xls:FAILED",
                "二.csv:CANCELLED", "一.csv:APPLIED");

        // 已上架那张的原件没了，问题行照样能导出（确认时已入库）
        ElcStockBatch b = batch(no(applied));
        Files.delete(files.root().resolve("applied").resolve(b.getFilePath()));
        List<List<String>> rows = SheetReader.read(
                raw(get("/elec/b/stock/batch/" + no(applied) + "/problems"), user).getContentAsByteArray(), 100);
        assertThat(rows).hasSize(2);
        assertThat(rows.get(1).get(3)).contains("B3");
        JsonNode detail = data(get("/elec/b/stock/batch/" + no(applied)), user, null);
        assertThat(detail.get("issueCounts").get("QTY_INVALID").asInt()).isEqualTo(1);
    }

    @Test
    @DisplayName("ac24 ★★★ 别人的批次：详情 / 行 / 导出 / 放弃都是 404（不泄露批次存在）")
    void ac24_othersBatchIs404() throws Exception {
        String owner = supplier("12600980016", "主人电子");
        String other = supplier("12600980017", "路人电子");
        String bn = no(upload(owner, "o.csv", "型号,数量\n" + prefix() + "P1,1\n", "MERGE"));
        assertThat(call(get("/elec/b/stock/batch/" + bn), other, null).get("code").asInt()).isEqualTo(10404);
        assertThat(call(get("/elec/b/stock/batch/" + bn + "/rows"), other, null).get("code").asInt()).isEqualTo(10404);
        assertThat(raw(get("/elec/b/stock/batch/" + bn + "/problems"), other).getContentAsString())
                .contains("10404");
        assertThat(call(delete("/elec/b/stock/batch/" + bn), other, null).get("code").asInt()).isEqualTo(10404);
        assertThat(call(post("/elec/b/stock/batch/" + bn + "/apply"), other, null).get("code").asInt())
                .isEqualTo(10404);
    }

    // ── 工具 ────────────────────────────────────────────────────────────────

    private void insertBatch(String batchNo, String supplierNo, String status, String path, String area) {
        ElcStockBatch b = new ElcStockBatch();
        b.setBatchNo(batchNo);
        b.setSupplierNo(supplierNo);
        b.setMode("MERGE");
        b.setStatus(status);
        b.setFilePath(path);
        b.setFileArea(area);
        b.setCreatedAt(LocalDateTime.now());
        batchMapper.insert(b);
    }

    private void backdate(String batchNo, int minutes) {
        batchMapper.update(null, Wrappers.<ElcStockBatch>lambdaUpdate().eq(ElcStockBatch::getBatchNo, batchNo)
                .set(ElcStockBatch::getCreatedAt, LocalDateTime.now().minusMinutes(minutes)));
    }

    /** 内存里有就回非 null（不触发重建） */
    private Object cache(String batchNo) {
        return cache.contains(batchNo) ? batchNo : null;
    }

    private ElcStockBatch batch(String batchNo) {
        return batchMapper.selectOne(Wrappers.<ElcStockBatch>lambdaQuery().eq(ElcStockBatch::getBatchNo, batchNo));
    }

    private long rowCount(String batchNo) {
        return rowMapper.selectCount(Wrappers.<ElcStockBatchRow>lambdaQuery().eq(ElcStockBatchRow::getBatchNo, batchNo));
    }

    private long countStock(String p) {
        return stockMapper.selectCount(Wrappers.<ElcStock>lambdaQuery().likeRight(ElcStock::getMpnNorm, p));
    }

    private long countOn(String p) {
        return stockMapper.selectCount(Wrappers.<ElcStock>lambdaQuery().likeRight(ElcStock::getMpnNorm, p)
                .eq(ElcStock::getStatus, ElcStock.STATUS_ON));
    }

    private JsonNode rows(String user, JsonNode pv, String view) throws Exception {
        return data(get("/elec/b/stock/batch/" + no(pv) + "/rows").param("view", view), user, null);
    }

    private void applyCsv(String user, String csv) throws Exception {
        JsonNode pv = upload(user, "s.csv", csv, "MERGE");
        apply(user, pv, null);
    }

    private void apply(String user, JsonNode pv, Integer expectDelist) throws Exception {
        data(post("/elec/b/stock/batch/" + no(pv) + "/apply"), user,
                expectDelist == null ? null : "{\"expectDelist\":" + expectDelist + "}");
    }

    private static String no(JsonNode pv) {
        return pv.get("batchNo").asString();
    }

    private static String prefix() {
        return "ZU" + (System.nanoTime() % 1_000_000) + "N" + SEQ.incrementAndGet();
    }

    private String supplier(String phone, String company) throws Exception {
        String user = main.consumer(phone);
        data(post("/elec/b/supplier"), user, "{\"companyName\":\"" + company + "\",\"contactName\":\"测\"}");
        return user;
    }

    private JsonNode upload(String user, String name, String csv, String mode) throws Exception {
        JsonNode r = uploadRaw(user, name, csv.getBytes(StandardCharsets.UTF_8), mode);
        assertThat(r.get("code").asInt()).as(r.toString()).isZero();
        return r.get("data");
    }

    private JsonNode uploadNamed(String user, String tmpName, String name, byte[] bytes, String mode) throws Exception {
        JsonNode r = uploadRawNamed(user, tmpName, name, bytes, mode);
        assertThat(r.get("code").asInt()).as(r.toString()).isZero();
        return r.get("data");
    }

    private JsonNode uploadRaw(String user, String name, byte[] bytes, String mode) throws Exception {
        return uploadRawNamed(user, name, null, bytes, mode);
    }

    private JsonNode uploadRawNamed(String user, String tmpName, String name, byte[] bytes, String mode)
            throws Exception {
        var req = multipart("/elec/b/stock/upload")
                .file(new MockMultipartFile("file", tmpName, "application/octet-stream", bytes))
                .param("mode", mode)
                .header("Authorization", "Bearer " + user);
        if (name != null) {
            req.param("name", name);
        }
        return json.readTree(mvc().perform(req).andReturn().getResponse().getContentAsString());
    }

    private MockHttpServletResponse raw(MockHttpServletRequestBuilder req, String token) throws Exception {
        req.header("Authorization", "Bearer " + token);
        return mvc().perform(req).andReturn().getResponse();
    }

    private JsonNode data(MockHttpServletRequestBuilder req, String token, String body) throws Exception {
        JsonNode r = call(req, token, body);
        assertThat(r.get("code").asInt()).as(r.toString()).isZero();
        return r.path("data");
    }

    private JsonNode call(MockHttpServletRequestBuilder req, String token, String body) throws Exception {
        if (token != null) {
            req.header("Authorization", "Bearer " + token);
        }
        if (body != null) {
            req.contentType(MediaType.APPLICATION_JSON).content(body);
        }
        return json.readTree(mvc().perform(req).andReturn().getResponse().getContentAsString());
    }
}
