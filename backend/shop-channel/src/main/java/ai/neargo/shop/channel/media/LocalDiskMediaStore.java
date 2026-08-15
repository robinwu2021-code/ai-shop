package ai.neargo.shop.channel.media;

import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.media.MediaStore;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.Collection;
import java.util.HexFormat;

/**
 * 本地磁盘实现 —— <b>一期形态，生产最终要换成 COS</b>（见
 * <a href="../../../../../../../../../../docs/technical/design/资源需求评估-JDK21与native.md">资源需求评估</a> §L3-8：
 * 决定性的理由是带宽，云主机按固定带宽计费，十来个人同时刷首页就打满了）。
 *
 * <p>之所以先做这一版：接 COS 需要一套凭据与回源域名，而在它们到位之前
 * 整条链路完全跑不通 —— B 端连一张商品图都传不了。
 *
 * <p>换 COS 时<b>只删这个类、加一个 {@code CosMediaStore}</b>。
 * key 的形状（{@code E0001/S0003/goods/202608/9f2c….jpg}）逐字就是 COS 的 object key，
 * 不需要任何映射层。
 */
@Slf4j
@Component
public class LocalDiskMediaStore implements MediaStore {

    /** HMAC 算法。与 COS 的预签名同族，换过去时签名语义不变。 */
    private static final String HMAC = "HmacSHA256";

    private final Path root;
    private final String publicPrefix;
    private final String privatePrefix;
    private final byte[] signKey;

    public LocalDiskMediaStore(@Value("${shop.upload.dir:./data/uploads}") String dir,
                               @Value("${shop.upload.public-prefix:/uploads}") String publicPrefix,
                               @Value("${shop.upload.private-prefix:/media}") String privatePrefix,
                               @Value("${shop.media.sign-secret:}") String signSecret) {
        this.root = Path.of(dir).toAbsolutePath().normalize();
        this.publicPrefix = publicPrefix;
        this.privatePrefix = privatePrefix;

        if (signSecret == null || signSecret.isBlank()) {
            /*
             * 没配就每次启动随机一把。**绝不能给一个固定的默认值** ——
             * 那等于把签名密钥写进代码库，签名当场形同虚设。
             *
             * 随机的后果是重启后旧签名 URL 全部失效，表现为「运营端证件图裂了，刷一下又好」。
             * 本地开发无所谓（也正因如此才不做启动即失败，否则每个人跑起来前都得先配一个）；
             * 生产必须配 SHOP_MEDIA_SIGN_SECRET，这条 WARN 就是给那时候看的。
             */
            byte[] random = new byte[32];
            new SecureRandom().nextBytes(random);
            this.signKey = random;
            log.warn("shop.media.sign-secret 未配置，已随机生成 —— 重启后已发出的签名 URL 全部失效"
                    + "（证件图会裂，刷新后恢复）。生产环境必须配置 SHOP_MEDIA_SIGN_SECRET。");
        } else {
            this.signKey = signSecret.getBytes(StandardCharsets.UTF_8);
        }
    }

    @Override
    public void put(String key, InputStream in, long size, String contentType) {
        Path target = resolve(key);
        try {
            Files.createDirectories(target.getParent());
            // REPLACE_EXISTING：key 里带 UUID，正常撞不上；真撞上了也是覆盖成同一份内容更安全，
            // 而不是抛异常让调用方留下一条 PENDING 记账行永远转不成 ACTIVE
            Files.copy(in, target, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            throw new IllegalStateException("写入失败：" + key, e);
        }
    }

    @Override
    public void delete(Collection<String> keys) {
        for (String key : keys) {
            try {
                // deleteIfExists 而不是 delete：回收批次要能整批重跑，
                // 上一轮已经删掉的那些不该让重跑整批失败
                Files.deleteIfExists(resolve(key));
            } catch (IOException e) {
                // 单个删不掉不该中断整批：批次会把它记成失败，运营可以重跑
                log.warn("删除失败，留给批次重跑：{}", key, e);
            }
        }
    }

    @Override
    public boolean exists(String key) {
        return Files.isRegularFile(resolve(key));
    }

    @Override
    public String publicUrl(String key) {
        return publicPrefix + "/" + key;
    }

    @Override
    public String privatePath(String key) {
        return privatePrefix + "/" + key;
    }

    @Override
    public String signedUrl(String key, Duration ttl) {
        long exp = System.currentTimeMillis() / 1000 + ttl.toSeconds();
        return privatePath(key) + "?exp=" + exp + "&sig=" + sign(key, exp);
    }

    /**
     * 校验签名。给读取侧的过滤器用。
     *
     * <p>用 {@link java.security.MessageDigest#isEqual} 做定长比较而不是
     * {@code String.equals} —— 后者会在第一个不同的字符处返回，
     * 逐字节的耗时差异足以把签名一位一位试出来。
     */
    public boolean verify(String key, long exp, String sig) {
        if (exp < System.currentTimeMillis() / 1000) {
            return false;
        }
        return java.security.MessageDigest.isEqual(
                sign(key, exp).getBytes(StandardCharsets.UTF_8),
                sig == null ? new byte[0] : sig.getBytes(StandardCharsets.UTF_8));
    }

    private String sign(String key, long exp) {
        try {
            Mac mac = Mac.getInstance(HMAC);
            mac.init(new SecretKeySpec(signKey, HMAC));
            return HexFormat.of().formatHex(mac.doFinal((key + "|" + exp).getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("签名失败", e);
        }
    }

    /**
     * key → 磁盘路径，并确认落点在根目录内。
     *
     * <p>key 的各段虽然都来自登录态与 UUID、正常不含 {@code ..}，
     * 但这行的成本是零，而漏了它就是一个任意文件读写。
     */
    private Path resolve(String key) {
        Path target = root.resolve(key).normalize();
        if (!target.startsWith(root)) {
            throw BizException.of(ErrorCode.BAD_REQUEST);
        }
        return target;
    }
}
