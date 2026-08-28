package ai.neargo.shop.scenario;

import ai.neargo.shop.media.MediaStore;
import ai.neargo.shop.media.SysMediaAsset;
import ai.neargo.shop.media.SysMediaAssetMapper;
import ai.neargo.shop.support.TestLogin;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
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
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 图片上传的四层目录、记账、以及<b>证件不能从公开目录拿到</b>。
 *
 * <p>最后那一条是这个测试类存在的主要理由：在四层目录之前，证件与商品图落在同一个
 * {@code permitAll} 的目录下，营业执照的 URL 一旦进了数据库导出或运营端截图，
 * 谁都能拉到原件 —— 而这件事没有任何症状，上传返回的是 200，图也确实打得开。
 *
 * <p><b>撤掉 {@code UploadResourceConfig} 的 GoodsOnlyInterceptor，
 * {@link #qualNotReachableFromPublicDir} 必须变红。</b>
 */
@SpringBootTest
@ActiveProfiles("test")
class MediaUploadFlowTest {


    @Autowired
    private ai.neargo.shop.common.OtpStore otpStore;
    @Autowired
    private WebApplicationContext context;
    @Autowired
    private ObjectMapper json;
    @Autowired
    private SysMediaAssetMapper assetMapper;
    @Autowired
    private MediaStore mediaStore;

    private MockMvc mvc() {
        return MockMvcBuilders.webAppContextSetup(context)
                .apply(org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers
                        .springSecurity())
                .build();
    }

    @Test
    @DisplayName("商品图：落四层路径，记账为 ACTIVE，宽高读得到")
    void goodsImageLandsInFourLevelPathAndIsAccounted() throws Exception {
        String token = merchant();

        String url = upload(token, null, png(120, 80));

        // /uploads/{主体}/{门店}/goods/{年月}/{随机}.png
        assertThat(url).startsWith("/uploads/");
        String key = url.substring("/uploads/".length());
        String[] seg = key.split("/");
        assertThat(seg).hasSize(5);
        assertThat(seg[2]).isEqualTo("goods");
        assertThat(seg[3]).matches("\\d{6}");

        SysMediaAsset row = assetMapper.selectOne(Wrappers.<SysMediaAsset>lambdaQuery()
                .eq(SysMediaAsset::getAssetKey, key));
        assertThat(row).isNotNull();
        // 三步顺序跑完才是 ACTIVE —— 停在 PENDING 说明落盘或第三步没走到
        assertThat(row.getStatus()).isEqualTo(SysMediaAsset.ACTIVE);
        assertThat(row.getBizType()).isEqualTo(SysMediaAsset.GOODS);
        assertThat(row.getBytes()).isPositive();
        // 只读文件头就该拿到的两个数
        assertThat(row.getWidth()).isEqualTo(120);
        assertThat(row.getHeight()).isEqualTo(80);
        // 归属：门店段与记账行必须是同一个值，否则统计和清单会对不上
        assertThat(row.getStoreNo()).isEqualTo(seg[1]);

        // 公开图必须真的打得开，否则商品页就是一张裂图
        mvc().perform(get(url)).andExpect(status().isOk());
    }

    @Test
    @DisplayName("证件：归到主体级 _ENTITY，返回私有路径而不是公开路径")
    void qualIsEntityScopedAndPrivate() throws Exception {
        String token = merchant();

        String url = upload(token, SysMediaAsset.QUAL, png(60, 60));

        assertThat(url).startsWith("/media/");
        String key = url.substring("/media/".length());
        assertThat(key.split("/")[1]).isEqualTo(SysMediaAsset.ENTITY_SCOPE);

        SysMediaAsset row = assetMapper.selectOne(Wrappers.<SysMediaAsset>lambdaQuery()
                .eq(SysMediaAsset::getAssetKey, key));
        assertThat(row.getBizType()).isEqualTo(SysMediaAsset.QUAL);
        assertThat(row.getStoreNo()).isEqualTo(SysMediaAsset.ENTITY_SCOPE);
    }

    /**
     * <b>后缀是客户端说了算的，所以类型判定不能只看后缀。</b>
     *
     * <p>实测过的真实后果：一段纯文本改名 {@code x.png} 传上去，白名单放行、
     * {@code dimensionsOf} 读不出尺寸也不拦（它的注释明说「读不出尺寸不该让上传失败」），
     * 于是任意字节以 {@code Content-Type: image/png} 落进<b>公开桶</b>并可公开取回 ——
     * 生产上确实存进去了两个这样的文件。记账表里 width/height 是 NULL，
     * 但没有任何人会去看那两列，所以这件事同样<b>没有症状</b>。
     *
     * <p><b>撤掉 {@code BizUploadController.looksLikeImage}，这个用例必须变红。</b>
     */
    @Test
    @DisplayName("不是图的字节传不上去 —— 后缀白名单挡不住改名")
    void nonImageBytesAreRejectedEvenWithImageExtension() throws Exception {
        String token = merchant();

        // ① 纯文本改名 .png
        mvc().perform(multipart("/biz/upload/image")
                        .file(new MockMultipartFile("file", "a.png", "image/png",
                                "this-is-not-an-image".repeat(20).getBytes()))
                        .header("Authorization", "Bearer " + token))
                .andExpect(jsonPath("$.code").value(org.hamcrest.Matchers.not(0)));

        // ② 真图但后缀对不上：JPEG 字节叫 .png。
        //    存下来 Content-Type 与真实字节不符，按 Content-Type 分发的下游会拿到
        //    一个它处理不了的东西，而且报错报在离上传很远的地方。
        ByteArrayOutputStream jpg = new ByteArrayOutputStream();
        ImageIO.write(new BufferedImage(40, 40, BufferedImage.TYPE_INT_RGB), "jpg", jpg);
        mvc().perform(multipart("/biz/upload/image")
                        .file(new MockMultipartFile("file", "b.png", "image/png", jpg.toByteArray()))
                        .header("Authorization", "Bearer " + token))
                .andExpect(jsonPath("$.code").value(org.hamcrest.Matchers.not(0)));
    }

    @Test
    @DisplayName("证件从公开目录拿不到 —— 这是整个四层目录的主要理由")
    void qualNotReachableFromPublicDir() throws Exception {
        String token = merchant();
        String key = upload(token, SysMediaAsset.QUAL, png(50, 50)).substring("/media/".length());

        // 拿着真实的 key 去公开前缀下要 —— 撤掉 GoodsOnlyInterceptor 这里就变 200
        mvc().perform(get("/uploads/" + key)).andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("签名 URL 能打开；篡改一位就打不开，且是 404 不是 403")
    void signedUrlOpensAndTamperedOneDoesNot() throws Exception {
        String token = merchant();
        String key = upload(token, SysMediaAsset.QUAL, png(40, 40)).substring("/media/".length());

        String signed = mediaStore.signedUrl(key, Duration.ofMinutes(5));
        mvc().perform(get(signed)).andExpect(status().isOk());

        // 把签名最后一位改掉。404 而不是 403：403 等于承认这个 key 下确实有东西
        String tampered = signed.substring(0, signed.length() - 1)
                + (signed.endsWith("0") ? "1" : "0");
        mvc().perform(get(tampered)).andExpect(status().isNotFound());

        // 没有签名同样打不开
        mvc().perform(get("/media/" + key)).andExpect(status().isNotFound());
    }

    // ---------------------------------------------------------------- 器具

    /** 真的编码一张 PNG：宽高断言要有意义，就不能拿假字节糊弄过去。 */
    private static byte[] png(int w, int h) throws Exception {
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(img, "png", out);
        return out.toByteArray();
    }

    private String upload(String token, String bizType, byte[] bytes) throws Exception {
        var req = multipart("/biz/upload/image")
                .file(new MockMultipartFile("file", "a.png", "image/png", bytes))
                .header("Authorization", "Bearer " + token);
        if (bizType != null) {
            req = req.param("bizType", bizType);
        }
        String body = mvc().perform(req)
                .andExpect(jsonPath("$.code").value(0))
                .andReturn().getResponse().getContentAsString();
        return json.readTree(body).get("data").get("url").asString();
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
            cachedToken = merchantOnce("13500136001", "记账店");
        }
        return cachedToken;
    }

    /** 走完「入驻 → 通过 → 重新登录」，返回可用于 /biz/** 的 token。 */
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

        String bd = opsLogin();
        mvc().perform(post("/ops/merchant/apply/" + applyNo + "/audit")
                        .header("Authorization", "Bearer " + bd)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"approved\":true}"))
                .andExpect(jsonPath("$.code").value(0));
        // 商家身份是登录时解析进 BizContext 的，旧 token 上还没有
        // A7：/biz/** 只认 btk_，这里必须换 B 端令牌
        return TestLogin.merchantOwner(mvc(), json, otpStore, phone);
    }

    private String opsLogin() throws Exception {
        String body = mvc().perform(post("/ops/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"bd\",\"password\":\"bd123\"}"))
                .andReturn().getResponse().getContentAsString();
        return json.readTree(body).get("data").get("token").asString();
    }
}
