package ai.neargo.shop.scenario;

import ai.neargo.shop.media.MediaScanner;
import ai.neargo.shop.media.SysMediaAsset;
import ai.neargo.shop.media.SysMediaAssetMapper;
import ai.neargo.shop.product.entity.PrdGoods;
import ai.neargo.shop.product.mapper.ProductMappers;
import ai.neargo.shop.support.TestLogin;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;

/**
 * 标记扫描：谁进待回收清单、谁被救回、以及<b>扫描一个文件都不删</b>。
 *
 * <p>这里的每一条都对应一种真实的翻车方式：
 * <ul>
 *   <li>替换图不进清单 → 空间只涨不降，而且没人会注意到</li>
 *   <li>在用图进了清单 → 运营照着清单删，商品页集体裂</li>
 *   <li>宽限期内就进清单 → 商家刚传完还没保存，图就被列成垃圾</li>
 *   <li>救不回来 → 误判不可逆</li>
 * </ul>
 */
@SpringBootTest
@ActiveProfiles("test")
class MediaScanFlowTest {


    @Autowired
    private ai.neargo.shop.common.OtpStore otpStore;
    @Autowired
    private WebApplicationContext context;
    @Autowired
    private ObjectMapper json;
    @Autowired
    private SysMediaAssetMapper assetMapper;
    @Autowired
    private ProductMappers.GoodsMapper goodsMapper;
    @Autowired
    private MediaScanner scanner;

    /** 上传根目录由 application-testcfg.yml 给（target/test-uploads），不再各测试类自造。 */
    @org.springframework.beans.factory.annotation.Value("${shop.upload.dir}")
    private String uploadDir;

    /**
     * 被改过 cover 的那个商品与它原来的值。
     *
     * <p><b>必须还回去。</b> 种子商品是<b>整套测试共用</b>的，而这里为了造出
     * 「图被引用 / 被替换」的场景会去改它的 cover。不还原的话，后面跑的用例拿到的是
     * 一个指向本用例上传文件的商品 —— 单跑这个类一切正常，跑全量才出问题，
     * 而报错会出现在一个跟图片毫无关系的用例上（本轮就是这么发现的：
     * 通知与工单那一批集体变红，单跑却全绿）。
     */
    private Long touchedGoodsId;
    private String originalCover;

    @AfterEach
    void restoreCover() {
        if (touchedGoodsId == null) {
            return;
        }
        PrdGoods back = new PrdGoods();
        back.setId(touchedGoodsId);
        back.setCover(originalCover == null ? "" : originalCover);
        goodsMapper.updateById(back);
        touchedGoodsId = null;
        originalCover = null;
    }

    @Test
    @DisplayName("替换图进清单，而新图仍在用 —— 这是量最大的一类")
    void replacedImageBecomesReclaimableButTheNewOneStaysActive() throws Exception {
        String token = merchant();
        String oldKey = uploadKey(token);
        String newKey = uploadKey(token);

        PrdGoods goods = anyGoods();
        // 先挂旧图：这一刻两张都在用
        setCover(goods, oldKey);
        backdate(oldKey);
        backdate(newKey);
        scanner.scan();
        assertThat(status(oldKey)).isEqualTo(SysMediaAsset.ACTIVE);

        // 换成新图 —— 旧图从此没人引用
        setCover(goods, newKey);
        scanner.scan();

        assertThat(status(oldKey)).isEqualTo(SysMediaAsset.RECLAIMABLE);
        assertThat(status(newKey)).isEqualTo(SysMediaAsset.ACTIVE);

        // 理由要能说出它原来挂在哪 —— 运营端那一列就是靠它
        SysMediaAsset old = row(oldKey);
        assertThat(old.getLastRefDesc()).contains("商品 · 主图");
        assertThat(old.getLastReferencedAt()).isNotNull();
        assertThat(old.getMarkedAt()).isNotNull();
    }

    @Test
    @DisplayName("宽限期内的新图不进清单 —— 商家刚传完还没点保存")
    void freshUploadIsNotMarkedWithinGracePeriod() throws Exception {
        String token = merchant();
        String key = uploadKey(token);

        // 不 backdate：它刚传上来，确实没人引用，但此刻列进清单就是错的
        scanner.scan();

        assertThat(status(key)).isEqualTo(SysMediaAsset.ACTIVE);
    }

    @Test
    @DisplayName("已在清单里的图又被引用 → 救回 ACTIVE，且 marked_at 真的置空")
    void reclaimableIsRescuedWhenReferencedAgain() throws Exception {
        String token = merchant();
        String key = uploadKey(token);
        backdate(key);

        scanner.scan();
        assertThat(status(key)).isEqualTo(SysMediaAsset.RECLAIMABLE);

        setCover(anyGoods(), key);
        scanner.scan();

        SysMediaAsset back = row(key);
        assertThat(back.getStatus()).isEqualTo(SysMediaAsset.ACTIVE);
        // 不置空的话，下次进清单会用一个过期的起算点，「待了多少天」从此是错的
        assertThat(back.getMarkedAt()).isNull();
    }

    @Test
    @DisplayName("扫描一个文件都不删 —— 本期不做自动回收")
    void scanNeverTouchesFiles() throws Exception {
        String token = merchant();
        String key = uploadKey(token);
        backdate(key);

        long before = countFiles();
        scanner.scan();

        assertThat(status(key)).isEqualTo(SysMediaAsset.RECLAIMABLE);
        assertThat(countFiles()).isEqualTo(before);
        assertThat(Files.exists(Path.of(uploadDir).resolve(key))).isTrue();
    }

    // ---------------------------------------------------------------- 器具

    private long countFiles() throws Exception {
        try (var s = Files.walk(Path.of(uploadDir))) {
            return s.filter(Files::isRegularFile).count();
        }
    }

    private String status(String key) {
        return row(key).getStatus();
    }

    private SysMediaAsset row(String key) {
        return assetMapper.selectOne(Wrappers.<SysMediaAsset>lambdaQuery()
                .eq(SysMediaAsset::getAssetKey, key));
    }

    /** 把上传时间推回宽限期之前 —— 模拟「传上来有些日子了」，不用真等 72 小时。 */
    private void backdate(String key) {
        SysMediaAsset upd = new SysMediaAsset();
        upd.setId(row(key).getId());
        upd.setCreatedAt(LocalDateTime.now().minusDays(30));
        assetMapper.updateById(upd);
    }

    private PrdGoods anyGoods() {
        PrdGoods g = goodsMapper.selectList(Wrappers.<PrdGoods>lambdaQuery()
                .last("limit 1")).stream().findFirst().orElse(null);
        assertThat(g).as("种子数据里应当有商品，否则这条测试证明不了什么").isNotNull();
        return g;
    }

    private void setCover(PrdGoods goods, String key) {
        if (touchedGoodsId == null) {
            touchedGoodsId = goods.getId();
            originalCover = goodsMapper.selectById(goods.getId()).getCover();
        }
        PrdGoods upd = new PrdGoods();
        upd.setId(goods.getId());
        upd.setCover("/uploads/" + key);
        goodsMapper.updateById(upd);
    }

    private String uploadKey(String token) throws Exception {
        BufferedImage img = new BufferedImage(30, 30, BufferedImage.TYPE_INT_RGB);
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
            cachedToken = merchantOnce("13500137001", "扫描店");
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
        return TestLogin.consumer(mvc(), json, otpStore, phone);
    }
}
