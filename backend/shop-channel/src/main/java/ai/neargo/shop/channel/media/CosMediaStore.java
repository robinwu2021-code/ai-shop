package ai.neargo.shop.channel.media;

import ai.neargo.shop.media.MediaStore;
import com.qcloud.cos.COSClient;
import com.qcloud.cos.ClientConfig;
import com.qcloud.cos.auth.BasicCOSCredentials;
import com.qcloud.cos.auth.COSCredentials;
import com.qcloud.cos.http.HttpMethodName;
import com.qcloud.cos.http.HttpProtocol;
import com.qcloud.cos.model.CannedAccessControlList;
import com.qcloud.cos.model.DeleteObjectsRequest;
import com.qcloud.cos.model.ObjectMetadata;
import com.qcloud.cos.model.PutObjectRequest;
import com.qcloud.cos.region.Region;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.io.InputStream;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Date;
import java.util.List;

/**
 * 腾讯云 COS 存储实现（对象存储，生产用）。
 *
 * <p>与 {@link LocalDiskMediaStore} 二选一，靠 {@code shop.media.provider=cos} 切换。
 * <b>object key 逐字就是 {@code MediaStore} 约定的相对 key</b>（见 BizUploadController 建 key 处），
 * 切换时目录结构、记账、回收逻辑一行都不用改。
 *
 * <p><b>公读 vs 私有靠 key 的用途段区分</b>（{@code entity/store/用途/yyyymm/名}）：
 * {@code goods} 段的图是给买家看的 → 上传即 public-read，{@link #publicUrl} 直出 CDN/COS 地址；
 * {@code qual}/{@code aftersale}（证件、售后凭证）→ 私有 ACL，只能通过 {@link #signedUrl} 带签名读。
 * 这与本地实现「公开读与签名读分开」的语义一致。
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "shop.media.provider", havingValue = "cos")
public class CosMediaStore implements MediaStore {

    /** key 的第 3 段（用途）为它时按公读上传。 */
    private static final String PUBLIC_SEGMENT = "goods";

    private final COSClient cos;
    private final String bucket;
    /** 公读图片对外前缀（CDN 或 COS 默认域名），末尾无 `/`。 */
    private final String baseUrl;

    public CosMediaStore(@Value("${shop.cos.secret-id:}") String secretId,
                         @Value("${shop.cos.secret-key:}") String secretKey,
                         @Value("${shop.cos.region:}") String region,
                         @Value("${shop.cos.bucket:}") String bucket,
                         @Value("${shop.cos.domain:}") String domain) {
        require(secretId, "COS_SECRET_ID");
        require(secretKey, "COS_SECRET_KEY");
        require(region, "COS_REGION");
        require(bucket, "COS_BUCKET");
        this.bucket = bucket;
        COSCredentials cred = new BasicCOSCredentials(secretId, secretKey);
        ClientConfig cfg = new ClientConfig(new Region(region));
        cfg.setHttpProtocol(HttpProtocol.https);
        this.cos = new COSClient(cred, cfg);
        this.baseUrl = (domain == null || domain.isBlank())
                ? "https://" + bucket + ".cos." + region + ".myqcloud.com"
                : domain.replaceAll("/+$", "");
        log.info("[media] COS 存储已启用 bucket={} region={} baseUrl={}", bucket, region, baseUrl);
    }

    private static void require(String v, String envName) {
        if (v == null || v.isBlank()) {
            throw new IllegalStateException(
                    "shop.media.provider=cos 但缺少配置：" + envName + " —— 配齐 COS 凭证再启动");
        }
    }

    /** goods 段公读，其余私有。key 段位见 BizUploadController：entity/store/用途/yyyymm/名。 */
    private static boolean isPublic(String key) {
        String[] seg = key.split("/");
        return seg.length >= 3 && PUBLIC_SEGMENT.equalsIgnoreCase(seg[2]);
    }

    @Override
    public void put(String key, InputStream in, long size, String contentType) {
        ObjectMetadata meta = new ObjectMetadata();
        meta.setContentLength(size);
        if (contentType != null && !contentType.isBlank()) {
            meta.setContentType(contentType);
        }
        PutObjectRequest req = new PutObjectRequest(bucket, key, in, meta);
        if (isPublic(key)) {
            req.setCannedAcl(CannedAccessControlList.PublicRead);
        }
        cos.putObject(req);
    }

    @Override
    public void delete(Collection<String> keys) {
        if (keys == null || keys.isEmpty()) {
            return;
        }
        // COS 批量删单次上限 1000，分批
        List<String> all = new ArrayList<>(keys);
        for (int i = 0; i < all.size(); i += 1000) {
            List<DeleteObjectsRequest.KeyVersion> batch = new ArrayList<>();
            for (String k : all.subList(i, Math.min(i + 1000, all.size()))) {
                batch.add(new DeleteObjectsRequest.KeyVersion(k));
            }
            DeleteObjectsRequest req = new DeleteObjectsRequest(bucket).withKeys(batch);
            cos.deleteObjects(req); // 幂等：不存在的 key 不报错
        }
    }

    @Override
    public boolean exists(String key) {
        return cos.doesObjectExist(bucket, key);
    }

    @Override
    public String publicUrl(String key) {
        return baseUrl + "/" + key;
    }

    @Override
    public String privatePath(String key) {
        // 存库的稳定标识 = object key 本身；渲染那一刻用 signedUrl(key) 换签名 URL。
        return key;
    }

    @Override
    public String signedUrl(String key, Duration ttl) {
        Date expire = new Date(System.currentTimeMillis() + ttl.toMillis());
        return cos.generatePresignedUrl(bucket, key, expire, HttpMethodName.GET).toString();
    }

    @PreDestroy
    void shutdown() {
        cos.shutdown();
    }
}
