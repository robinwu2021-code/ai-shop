package ai.neargo.shop.scenario;

import ai.neargo.shop.media.MediaBackfillService;
import ai.neargo.shop.media.SysMediaAsset;
import ai.neargo.shop.media.SysMediaAssetMapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.time.temporal.ChronoUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 磁盘对账：磁盘上有、库里没有的文件要能补回来。
 *
 * <p>这类文件是<b>查不出来的</b> —— 统计不算它、回收清单不出现它，
 * 只有人去 {@code du} 才发现磁盘满了。所以这条链路本身就是一道防线。
 */
@SpringBootTest
@ActiveProfiles("test")
class MediaBackfillFlowTest {

    @Autowired
    private MediaBackfillService backfillService;
    @Autowired
    private SysMediaAssetMapper assetMapper;

    @Value("${shop.upload.dir}")
    private String uploadDir;

    @Test
    @DisplayName("存量两段式路径：归到主体级 _ENTITY，不去猜一个默认门店")
    void legacyTwoSegmentFileIsBackfilledAsEntityScoped() throws Exception {
        String key = "MLEGACY001/legacy-" + System.nanoTime() + ".png";
        Path file = writePng(key, 90);

        MediaBackfillService.Result r = backfillService.backfill();
        assertThat(r.inserted()).isPositive();

        SysMediaAsset row = row(key);
        assertThat(row).isNotNull();
        assertThat(row.getEntityNo()).isEqualTo("MLEGACY001");
        // 这些文件是在门店维度出现之前传的，它们属于哪家店没有答案 ——
        // 填一个默认店会让统计看起来精确而实际是编的
        assertThat(row.getStoreNo()).isEqualTo(SysMediaAsset.ENTITY_SCOPE);
        assertThat(row.getBizType()).isEqualTo(SysMediaAsset.GOODS);
        assertThat(row.getStatus()).isEqualTo(SysMediaAsset.ACTIVE);
        assertThat(row.getBytes()).isEqualTo(Files.size(file));
        assertThat(row.getWidth()).isEqualTo(90);

        // created_at 取文件时间而不是 now()：用 now() 的话补录当天全在宽限期内，
        // 第一次扫描一张都不进清单，看起来像「扫描没生效」
        assertThat(row.getCreatedAt()).isBefore(java.time.LocalDateTime.now().minusDays(100));
    }

    @Test
    @DisplayName("新四层路径：主体/门店/用途按路径还原，不当成存量")
    void fourLevelPathKeepsItsStoreAndBizType() throws Exception {
        String key = "E9001/S9001/qual/202601/backfill-" + System.nanoTime() + ".png";
        writePng(key, 40);

        backfillService.backfill();

        SysMediaAsset row = row(key);
        assertThat(row.getEntityNo()).isEqualTo("E9001");
        assertThat(row.getStoreNo()).isEqualTo("S9001");
        assertThat(row.getBizType()).isEqualTo(SysMediaAsset.QUAL);
    }

    @Test
    @DisplayName("幂等：跑两遍不会重复记账")
    void backfillIsIdempotent() throws Exception {
        String key = "MIDEMP001/x-" + System.nanoTime() + ".png";
        writePng(key, 30);

        backfillService.backfill();
        long after1 = count(key);
        MediaBackfillService.Result second = backfillService.backfill();
        long after2 = count(key);

        assertThat(after1).isEqualTo(1);
        assertThat(after2).isEqualTo(1);
        // 第二遍这一张必须落进 skipped，而不是又插一行
        assertThat(second.skipped()).isPositive();
    }

    @Test
    @DisplayName("非图片文件不记账 —— .DS_Store 那类东西不该变成一条资产")
    void nonImageFilesAreNotRecorded() throws Exception {
        String key = "MJUNK001/notes-" + System.nanoTime() + ".txt";
        Path file = Path.of(uploadDir).resolve(key);
        Files.createDirectories(file.getParent());
        Files.writeString(file, "not an image");

        backfillService.backfill();

        assertThat(count(key)).isZero();
    }

    // ---------------------------------------------------------------- 器具

    private Path writePng(String key, int size) throws Exception {
        Path file = Path.of(uploadDir).resolve(key);
        Files.createDirectories(file.getParent());
        BufferedImage img = new BufferedImage(size, size, BufferedImage.TYPE_INT_RGB);
        try (var out = Files.newOutputStream(file)) {
            ImageIO.write(img, "png", out);
        }
        // 做旧：补录要按文件时间落 created_at，这条断言才有意义
        Files.setLastModifiedTime(file,
                FileTime.from(Instant.now().minus(200, ChronoUnit.DAYS)));
        return file;
    }

    private SysMediaAsset row(String key) {
        return assetMapper.selectOne(Wrappers.<SysMediaAsset>lambdaQuery()
                .eq(SysMediaAsset::getAssetKey, key));
    }

    private long count(String key) {
        return assetMapper.selectCount(Wrappers.<SysMediaAsset>lambdaQuery()
                .eq(SysMediaAsset::getAssetKey, key));
    }
}
