package ai.neargo.shop.elec.svc;

import ai.neargo.shop.elec.service.impl.UploadHousekeeper;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDate;

/**
 * 每周一次：清上传原件（规则全在 {@link UploadHousekeeper}，这里只管按时叫它）。
 *
 * <p>时间由 {@code elec.upload-clean-cron} 配（默认每周一 3:30）；设成 {@code -} 关掉。
 * 不接 job 平台，理由同 {@link ElecExpiryReminder}：失败了下周再来，不涉及钱。
 */
@Component
public class ElecUploadCleaner {

    private final UploadHousekeeper housekeeper;

    public ElecUploadCleaner(UploadHousekeeper housekeeper) {
        this.housekeeper = housekeeper;
    }

    @Scheduled(cron = "${elec.upload-clean-cron:0 30 3 * * MON}")
    public void run() {
        housekeeper.run(LocalDate.now());
    }
}
