package ai.neargo.shop.portal.ops;

import ai.neargo.shop.auth.Perms;
import ai.neargo.shop.auth.SecurityUtils;
import ai.neargo.shop.common.PageData;
import ai.neargo.shop.media.MediaBackfillService;
import ai.neargo.shop.media.MediaPurgeService;
import ai.neargo.shop.media.MediaScanner;
import ai.neargo.shop.media.MediaUsageService;
import ai.neargo.shop.media.SysMediaPurgeBatch;
import org.springframework.context.annotation.Profile;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 平台端 · 存储空间治理（TDD-图片存储与空间回收 §L3-7）。
 *
 * <p><b>「能看」与「能删」是两个权限码</b>：看清单是日常巡检，删是一次性的破坏操作。
 * 合成一个的话，任何一个来看看占了多少空间的人手里都握着删除按钮。
 *
 * <p>住在 {@code portal} 而不是某个域的 {@code api} 包：图片记账是横切基础设施，
 * 它不属于商品也不属于商家 —— 与 {@code shop-base/media} 的位置逻辑一致。
 */
@Profile("ops")
@RestController
public class OpsMediaController {

    /** 一页 20 条：待回收清单每行带缩略图，再多一屏就翻不动了。 */
    private static final int DEFAULT_SIZE = 20;

    private final MediaUsageService usageService;
    private final MediaPurgeService purgeService;
    private final MediaScanner scanner;
    private final MediaBackfillService backfillService;

    public OpsMediaController(MediaUsageService usageService, MediaPurgeService purgeService,
                              MediaScanner scanner, MediaBackfillService backfillService) {
        this.usageService = usageService;
        this.purgeService = purgeService;
        this.scanner = scanner;
        this.backfillService = backfillService;
    }

    // ---------------------------------------------------------------- 看

    /** 顶部四张卡。{@code abnormal} 为真时前端置顶红条并禁用批量回收。 */
    @GetMapping("/ops/media/overview")
    @PreAuthorize("@perm.can('" + Perms.SYSTEM_MEDIA_READ + "')")
    public MediaUsageService.OverviewVO overview() {
        return usageService.overview();
    }

    /** 门店占用，默认按待回收倒序。 */
    @GetMapping("/ops/media/stores")
    @PreAuthorize("@perm.can('" + Perms.SYSTEM_MEDIA_READ + "')")
    public List<MediaUsageService.StoreUsageVO> stores() {
        return usageService.stores();
    }

    /**
     * 待回收明细。
     *
     * @param includeQual 证件默认不在清单里 —— 它的留存期是法务口径，
     *                    不该由工程默认决定。要连证件一起清，得在界面上显式打开
     * @param neverUsed   {@code true} 只看「从未被引用」，{@code false} 只看「被替换掉的」
     */
    @GetMapping("/ops/media/reclaimable")
    @PreAuthorize("@perm.can('" + Perms.SYSTEM_MEDIA_READ + "')")
    public PageData<MediaUsageService.ReclaimableVO> reclaimable(
            @RequestParam(required = false) String entityNo,
            @RequestParam(required = false) String storeNo,
            @RequestParam(defaultValue = "false") boolean includeQual,
            @RequestParam(required = false) Boolean neverUsed,
            @RequestParam(defaultValue = "1") long page,
            @RequestParam(required = false) Long size) {
        return usageService.reclaimable(
                new MediaPurgeService.Filter(entityNo, storeNo, includeQual, neverUsed),
                page, size == null ? DEFAULT_SIZE : size);
    }

    @GetMapping("/ops/media/batches")
    @PreAuthorize("@perm.can('" + Perms.SYSTEM_MEDIA_READ + "')")
    public List<SysMediaPurgeBatch> batches() {
        return usageService.batches();
    }

    @GetMapping("/ops/media/batches/{batchNo}")
    @PreAuthorize("@perm.can('" + Perms.SYSTEM_MEDIA_READ + "')")
    public MediaUsageService.BatchDetailVO batch(@PathVariable String batchNo) {
        return usageService.batch(batchNo);
    }

    // ---------------------------------------------------------------- 动

    /**
     * 手动重扫。扫描是只读的 —— 它一个文件都不删，只重算「谁还被引用着」。
     *
     * <p>用 purge 权限而不是 read：虽然不删东西，但它会改变清单内容，
     * 而清单正是别人据以点删除的依据。
     */
    @PostMapping("/ops/media/scan")
    @PreAuthorize("@perm.can('" + Perms.SYSTEM_MEDIA_PURGE + "')")
    public MediaScanner.Result scan() {
        return scanner.scan();
    }

    /**
     * 磁盘对账：把「磁盘上有、记账表里没有」的文件补录进来。<b>幂等，可随时重跑。</b>
     *
     * <p>不只用于一次性的存量补录 —— 从备份恢复、手工拷贝之后同样需要它。
     * 那类文件是查不出来的：统计不算、清单不出现，只有人去 du 才发现磁盘满了。
     *
     * <p>用 purge 权限：它只增不删，但补录进来的行会立刻参与下一轮扫描，
     * 也就是会影响别人据以点删除的那份清单。
     */
    @PostMapping("/ops/media/backfill")
    @PreAuthorize("@perm.can('" + Perms.SYSTEM_MEDIA_PURGE + "')")
    public MediaBackfillService.Result backfill() {
        return backfillService.backfill();
    }

    /** 预览：这一票到底多少张、多少字节。运营在确认弹窗里看到的就是它。 */
    @PostMapping("/ops/media/purge/preview")
    @PreAuthorize("@perm.can('" + Perms.SYSTEM_MEDIA_PURGE + "')")
    public MediaPurgeService.Preview preview(@RequestBody PurgeReq req) {
        return purgeService.preview(new MediaPurgeService.Filter(
                req.entityNo(), req.storeNo(), req.includeQual(), req.neverUsed()));
    }

    /**
     * 提交回收。<b>不可逆</b>。
     *
     * <p>两种入参：勾选的 {@code assetKeys}，或跨页全选的 {@code filter + expectedCount}。
     * 后者<b>必须带数量</b>并由服务端比对 —— 从运营看到清单到点下确认，
     * 中间可能刚好跑过一次扫描把几张救回去了，不比对就会删掉他没看过的那几张。
     */
    @PostMapping("/ops/media/purge")
    @PreAuthorize("@perm.can('" + Perms.SYSTEM_MEDIA_PURGE + "')")
    public Map<String, String> purge(@RequestBody PurgeReq req) {
        var user = SecurityUtils.requireUser();
        String batchNo = purgeService.submit(
                req.assetKeys(),
                new MediaPurgeService.Filter(req.entityNo(), req.storeNo(),
                        req.includeQual(), req.neverUsed()),
                req.expectedCount(),
                user.userNo(), user.nickname());
        return Map.of("batchNo", batchNo);
    }

    public record PurgeReq(List<String> assetKeys, String entityNo, String storeNo,
                           boolean includeQual, Boolean neverUsed, Integer expectedCount) {
    }
}
