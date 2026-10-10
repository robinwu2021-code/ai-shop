package ai.neargo.shop.media;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Stream;

/**
 * 磁盘对账：把「磁盘上有、记账表里没有」的文件补录进来。
 *
 * <p><b>它不只是一次性的存量迁移。</b> 一开始是按「存量补记账脚本」设计的，
 * 但「磁盘上出现库里没有的文件」这件事在任何时候都可能发生 ——
 * 从备份恢复、手工拷贝、甚至上传在写库之前就崩掉。
 * 而这类文件是<b>查不出来的</b>：统计里不算它，回收清单里不出现它，
 * 只有人去 {@code du} 才发现磁盘满了。所以做成常驻能力，随时可重跑。
 *
 * <p><b>幂等</b>：已记账的 key 直接跳过。跑两遍与跑一遍结果一样。
 *
 * <p><b>不搬家、不洗业务表</b>：老路径原样记进 {@code asset_key}。
 * 回收是按记账表的行走的，目录前缀只是将来在 COS 上批量删的加速手段 ——
 * 为它做一次全量数据迁移不值。
 */
@Component
public class MediaBackfillService {

    private static final Logger log = LoggerFactory.getLogger(MediaBackfillService.class);

    /** 与上传端点同一份白名单：目录里的其它东西（.DS_Store、临时文件）不该被记成资产。 */
    private static final Set<String> ALLOWED = Set.of("jpg", "jpeg", "png", "webp", "gif");

    private final SysMediaAssetMapper assetMapper;
    private final Path root;

    public MediaBackfillService(SysMediaAssetMapper assetMapper,
                                @Value("${shop.upload.dir:./data/uploads}") String dir) {
        this.assetMapper = assetMapper;
        this.root = Path.of(dir).toAbsolutePath().normalize();
    }

    public Result backfill() {
        if (!Files.isDirectory(root)) {
            return new Result(0, 0, 0);
        }
        Set<String> known = new HashSet<>(assetMapper.selectList(
                        Wrappers.<SysMediaAsset>lambdaQuery().select(SysMediaAsset::getAssetKey))
                .stream().map(SysMediaAsset::getAssetKey).toList());

        int scanned = 0;
        int inserted = 0;
        int skipped = 0;
        try (Stream<Path> files = Files.walk(root)) {
            for (Path f : files.filter(Files::isRegularFile).toList()) {
                scanned++;
                String key = root.relativize(f).toString().replace('\\', '/');
                if (known.contains(key) || !ALLOWED.contains(extensionOf(key))) {
                    skipped++;
                    continue;
                }
                assetMapper.insert(rowFor(key, f));
                inserted++;
            }
        } catch (Exception e) {
            throw new IllegalStateException("磁盘对账失败：" + root, e);
        }
        log.info("磁盘对账完成：扫描 {} 个文件，补录 {} 条，跳过 {} 个", scanned, inserted, skipped);
        return new Result(scanned, inserted, skipped);
    }

    private SysMediaAsset rowFor(String key, Path file) throws Exception {
        String[] seg = key.split("/");
        SysMediaAsset a = new SysMediaAsset();
        a.setAssetKey(key);

        if (seg.length >= 5) {
            // 新形态 {主体}/{门店}/{用途}/{年月}/{名}
            a.setEntityNo(seg[0]);
            a.setStoreNo(seg[1]);
            a.setBizType(seg[2].toUpperCase(Locale.ROOT));
        } else {
            /*
             * 存量形态 {商家}/{名}。
             *
             * **门店归到 _ENTITY，不去猜一个默认门店。** TDD 初稿写的是「取该主体的
             * 默认门店」，实现时改了：那个数据当时根本不存在 —— 这些文件是在门店维度
             * 出现之前传的，它们究竟属于哪家店没有答案。填一个默认店会让统计看起来
             * 精确而实际上是编的，而运营正要拿这个数去决定清谁。
             *
             * 归到主体级是<b>真话</b>：运营端会显示成「主体级」，一眼看得出
             * 「这批是老数据，没有门店归属」。
             *
             * 顺带避开一个依赖倒置：默认门店在 mch_store（shop-merchant），
             * 而这里是 shop-base —— 为补一列冗余去开一条 SPI 不成比例。
             */
            a.setEntityNo(seg[0]);
            a.setStoreNo(SysMediaAsset.ENTITY_SCOPE);
            // 老数据无法区分用途，且当时全都落在公开目录下 —— 记成 GOODS 是与事实一致的
            a.setBizType(SysMediaAsset.GOODS);
        }

        a.setBytes(Files.size(file));
        int[] wh = dimensionsOf(file);
        a.setWidth(wh[0] > 0 ? wh[0] : null);
        a.setHeight(wh[1] > 0 ? wh[1] : null);
        a.setStatus(SysMediaAsset.ACTIVE);
        /*
         * created_at 用文件的最后修改时间，不用 now()。
         * 用 now() 的话所有存量都落在同一刻，而**宽限期是按它算的** ——
         * 补录当天全部处于宽限期内，第一次扫描一张都不会进清单，
         * 看起来像「扫描没生效」。用文件时间则它们本来就是旧的，行为与事实一致。
         */
        LocalDateTime mtime = LocalDateTime.ofInstant(
                Files.getLastModifiedTime(file).toInstant(), java.time.ZoneId.systemDefault());
        a.setCreatedAt(mtime);
        a.setUpdatedAt(LocalDateTime.now());
        return a;
    }

    /** 只读文件头，不解码像素 —— 与上传端点同一个理由。 */
    private static int[] dimensionsOf(Path file) {
        try (InputStream in = Files.newInputStream(file);
             ImageInputStream iis = ImageIO.createImageInputStream(in)) {
            if (iis == null) {
                return new int[]{0, 0};
            }
            Iterator<ImageReader> readers = ImageIO.getImageReaders(iis);
            if (!readers.hasNext()) {
                return new int[]{0, 0};
            }
            ImageReader reader = readers.next();
            try {
                reader.setInput(iis);
                return new int[]{reader.getWidth(0), reader.getHeight(0)};
            } finally {
                reader.dispose();
            }
        } catch (Exception e) {
            return new int[]{0, 0};
        }
    }

    private static String extensionOf(String key) {
        int dot = key.lastIndexOf('.');
        return dot < 0 ? "" : key.substring(dot + 1).toLowerCase(Locale.ROOT);
    }

    /** @param skipped 已记账的 + 不是图片的 */
    public record Result(int scanned, int inserted, int skipped) {
    }
}
