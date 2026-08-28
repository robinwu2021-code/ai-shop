package ai.neargo.shop.scenario;

import ai.neargo.shop.common.BizException;
import ai.neargo.shop.media.MediaPurgeService;
import ai.neargo.shop.media.MediaScanner;
import ai.neargo.shop.media.MediaUsageService;
import ai.neargo.shop.media.SysMediaAsset;
import ai.neargo.shop.media.SysMediaAssetMapper;
import ai.neargo.shop.media.SysMediaPurgeBatch;
import ai.neargo.shop.media.SysMediaPurgeBatchMapper;
import ai.neargo.shop.support.TestLogin;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import tools.jackson.databind.ObjectMapper;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;

/**
 * 回收执行：文件真的没了、批次留痕、以及<b>数量对不上就整批拒绝</b>。
 *
 * <p>最后那一条是「人工确认」这件事的全部意义所在：运营在页面上看到的是某一刻的清单，
 * 而从他看到到点下确认之间，扫描可能刚好把几张救回去了。
 * 不比对数量的话，删掉的就是他<b>没看过</b>的那几张 —— 而这种错没有任何症状。
 */
@SpringBootTest
@ActiveProfiles("test")
class MediaPurgeFlowTest {

    @Autowired
    private ai.neargo.shop.common.OtpStore otpStore;
    @Autowired
    private WebApplicationContext context;
    @Autowired
    private ObjectMapper json;
    @Autowired
    private SysMediaAssetMapper assetMapper;
    @Autowired
    private SysMediaPurgeBatchMapper batchMapper;
    @Autowired
    private MediaPurgeService purgeService;
    @Autowired
    private MediaUsageService usageService;
    @Autowired
    private MediaScanner scanner;

    @Value("${shop.upload.dir}")
    private String uploadDir;

    @Test
    @DisplayName("勾选提交 → 执行后文件真的没了，记账行留着，批次记到人头上")
    void purgeActuallyDeletesFilesAndKeepsTheRecord() throws Exception {
        String key = reclaimableKey();
        Path file = Path.of(uploadDir).resolve(key);
        assertThat(file).exists();

        String batchNo = purgeService.submit(List.of(key), null, null, "ST-TEST", "测试运营");
        purgeService.runQueued();

        // 文件没了
        assertThat(file).doesNotExist();
        // 行还在 —— 删除不可逆，「什么时候删了什么」必须永远查得到
        SysMediaAsset row = row(key);
        assertThat(row).isNotNull();
        assertThat(row.getStatus()).isEqualTo(SysMediaAsset.PURGED);
        assertThat(row.getPurgedAt()).isNotNull();
        assertThat(row.getPurgeBatchNo()).isEqualTo(batchNo);

        SysMediaPurgeBatch batch = batch(batchNo);
        assertThat(batch.getStatus()).isEqualTo(SysMediaPurgeBatch.DONE);
        assertThat(batch.getPurgedCount()).isEqualTo(1);
        assertThat(batch.getOperator()).isEqualTo("ST-TEST");
        // 记当时的名字：人离职、账号改名之后，这条记录还得说得清是谁
        assertThat(batch.getOperatorName()).isEqualTo("测试运营");
    }

    @Test
    @DisplayName("跨页全选：数量对不上就整批拒绝，一个文件都不删")
    void crossPageSelectionIsRejectedWhenCountDrifted() throws Exception {
        String key = reclaimableKey();
        Path file = Path.of(uploadDir).resolve(key);

        var filter = new MediaPurgeService.Filter(null, storeOf(key), false, null);
        int actual = purgeService.preview(filter).count();

        // 谎报一个数 —— 模拟「运营看到的清单已经不是现在这份了」
        assertThatThrownBy(() -> purgeService.submit(null, filter, actual + 1,
                "ST-TEST", "测试运营"))
                .isInstanceOf(BizException.class);

        assertThat(file).exists();
        assertThat(row(key).getStatus()).isEqualTo(SysMediaAsset.RECLAIMABLE);
        assertThat(row(key).getPurgeBatchNo()).isNull();
    }

    @Test
    @DisplayName("勾选里混进一张已经不可回收的 → 整批拒绝，不做「能删的先删」")
    void partialSelectionIsRejectedRatherThanPartiallyExecuted() throws Exception {
        String good = reclaimableKey();
        String active = uploadKey(merchant());

        // active 那张没进清单（刚传、还在宽限期内），混在一起提交
        assertThatThrownBy(() -> purgeService.submit(List.of(good, active), null, null,
                "ST-TEST", "测试运营"))
                .isInstanceOf(BizException.class);

        // 部分执行会让运营以为删的就是他看到的那些，而少的那几张永远不会有人发现
        assertThat(Path.of(uploadDir).resolve(good)).exists();
        assertThat(row(good).getPurgeBatchNo()).isNull();
    }

    @Test
    @DisplayName("批次重跑幂等：已删的跳过，不报错也不重复计数")
    void rerunIsIdempotent() throws Exception {
        String key = reclaimableKey();

        String batchNo = purgeService.submit(List.of(key), null, null, "ST-TEST", "测试运营");
        purgeService.runQueued();
        purgeService.run(batch(batchNo));

        SysMediaPurgeBatch batch = batch(batchNo);
        assertThat(batch.getFailedCount()).isZero();
        // 第二遍一张都不该再算 —— 累计计数不能因为重跑而虚高
        assertThat(batch.getPurgedCount()).isEqualTo(1);
        assertThat(row(key).getStatus()).isEqualTo(SysMediaAsset.PURGED);
    }

    @Test
    @DisplayName("已挂上批次号的不再出现在待回收清单里 —— 同一张图不会属于两批")
    void claimedAssetsLeaveTheReclaimableList() throws Exception {
        String key = reclaimableKey();
        var filter = new MediaPurgeService.Filter(null, storeOf(key), false, null);

        /*
         * 断言「这一张在不在」而不是「一共几张」：整个类共用一个商家与门店
         * （见 merchant() 的说明），按门店数数会被同类里别的用例干扰。
         */
        assertThat(purgeService.preview(filter).sample()).contains(key);
        purgeService.submit(List.of(key), null, null, "ST-TEST", "测试运营");
        assertThat(purgeService.preview(filter).sample()).doesNotContain(key);
    }

    @Test
    @DisplayName("概览把在用与待回收分开算 —— 运营一眼看出多少是垃圾")
    void overviewSplitsActiveAndReclaimable() throws Exception {
        reclaimableKey();
        MediaUsageService.OverviewVO vo = usageService.overview();

        assertThat(vo.reclaimableCount()).isPositive();
        assertThat(vo.reclaimableBytes()).isPositive();
        assertThat(vo.totalCount()).isEqualTo(vo.activeCount() + vo.reclaimableCount());
        assertThat(vo.totalBytes()).isEqualTo(vo.activeBytes() + vo.reclaimableBytes());
    }

    // ---------------------------------------------------------------- 器具

    /** 传一张图、推回宽限期之前、扫一遍 —— 得到一个真的躺在待回收清单里的 key。 */
    private String reclaimableKey() throws Exception {
        String key = uploadKey(merchant());
        SysMediaAsset upd = new SysMediaAsset();
        upd.setId(row(key).getId());
        upd.setCreatedAt(LocalDateTime.now().minusDays(30));
        assetMapper.updateById(upd);
        scanner.scan();
        assertThat(row(key).getStatus()).isEqualTo(SysMediaAsset.RECLAIMABLE);
        return key;
    }

    private String storeOf(String key) {
        return row(key).getStoreNo();
    }

    private SysMediaAsset row(String key) {
        return assetMapper.selectOne(Wrappers.<SysMediaAsset>lambdaQuery()
                .eq(SysMediaAsset::getAssetKey, key));
    }

    private SysMediaPurgeBatch batch(String batchNo) {
        return batchMapper.selectOne(Wrappers.<SysMediaPurgeBatch>lambdaQuery()
                .eq(SysMediaPurgeBatch::getBatchNo, batchNo));
    }

    private String uploadKey(String token) throws Exception {
        BufferedImage img = new BufferedImage(20, 20, BufferedImage.TYPE_INT_RGB);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(img, "png", out);
        String body = mvc().perform(multipart("/biz/upload/image")
                        .file(new MockMultipartFile("file", "a.png", "image/png", out.toByteArray()))
                        .header("Authorization", "Bearer " + token))
                .andExpect(jsonPath("$.code").value(0))
                .andReturn().getResponse().getContentAsString();
        return json.readTree(body).get("data").get("url").asString().substring("/uploads/".length());
    }

    private MockMvc mvc() {
        return MockMvcBuilders.webAppContextSetup(context)
                .apply(org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers
                        .springSecurity())
                .build();
    }


    /**
     * 整个测试类<b>共用一个商家</b>，不是每个用例建一个。
     *
     * <p>建商家会往共享的 H2 里加主体、默认门店与账号，而<b>别的用例在按分页找种子数据</b>。
     * 每个用例各建一个的话，三个媒体测试类一共造 15 个商家 —— 单跑全绿，
     * 跑全量却把 M6aStoreAttributionFlowTest 那类「列表里应当找得到」的断言挤掉了，
     * 而报错出现在一个跟图片毫无关系的用例上。本轮就是这么发现的。
     */
    private static String cachedToken;

    private String merchant() throws Exception {
        if (cachedToken == null) {
            cachedToken = merchantOnce("13500138001", "回收店");
        }
        return cachedToken;
    }

    private String merchantOnce(String phone, String name) throws Exception {
        String user = TestLogin.consumer(mvc(), json, otpStore, phone);
        String body = mvc().perform(post("/mp/merchant/apply").header("Authorization", "Bearer " + user)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"" + name + "\",\"subject\":\"INDIVIDUAL_BIZ\","
                                + "\"contactName\":\"张三\",\"contactPhone\":\"13900000000\","
                                + "\"category\":\"食品\",\"serviceScope\":\"COMMUNITY\","
                                + "\"communityNos\":[\"CM001\"]}"))
                .andExpect(jsonPath("$.code").value(0))
                .andReturn().getResponse().getContentAsString();
        String applyNo = json.readTree(body).get("data").get("applyNo").asString();
        String bd = json.readTree(mvc().perform(post("/ops/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"bd\",\"password\":\"bd123\"}"))
                .andReturn().getResponse().getContentAsString()).get("data").get("token").asString();
        mvc().perform(post("/ops/merchant/apply/" + applyNo + "/audit")
                        .header("Authorization", "Bearer " + bd)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"approved\":true}"))
                .andExpect(jsonPath("$.code").value(0));
        // A7：/biz/** 只认 btk_，这里必须换 B 端令牌
        return TestLogin.merchantOwner(mvc(), json, otpStore, phone);
    }
}
