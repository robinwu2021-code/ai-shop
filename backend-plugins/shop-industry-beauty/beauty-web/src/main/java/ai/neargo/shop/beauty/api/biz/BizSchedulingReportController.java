package ai.neargo.shop.beauty.api.biz;

import ai.neargo.shop.auth.BizContext;
import ai.neargo.shop.auth.BizContextFilter;
import ai.neargo.shop.auth.SecurityUtils;
import ai.neargo.shop.beauty.config.ConditionalOnBeautyWebEnabled;
import ai.neargo.shop.beauty.dto.SchedulingReportQuery;
import ai.neargo.shop.beauty.dto.SchedulingReportVO;
import ai.neargo.shop.beauty.service.SchedulingReportService;
import ai.neargo.shop.spi.user.StoreDirectoryPort;
import java.time.OffsetDateTime;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Profile;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** BAPI-119 首期子集：服务业报表。门店与身份只取当前认证上下文；判权在服务层（预约查看）。 */
@RestController("beautyBizSchedulingReportController")
@Validated
@ConditionalOnBeautyWebEnabled
@Profile("api")
@RequestMapping("/biz/x/beauty/reports")
public class BizSchedulingReportController {
    private final ObjectProvider<SchedulingReportService> reports;
    private final ObjectProvider<StoreDirectoryPort> directories;

    public BizSchedulingReportController(ObjectProvider<SchedulingReportService> reports,
            ObjectProvider<StoreDirectoryPort> directories) {
        this.reports = reports;
        this.directories = directories;
    }

    /** from / to：ISO 8601 带偏移，左闭右开，最长 93 天。 */
    @GetMapping("/scheduling")
    public SchedulingReportVO scheduling(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) OffsetDateTime from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) OffsetDateTime to,
            @RequestHeader(value = BizContextFilter.STORE_HEADER, required = false) String requestedStore) {
        BookingStoreScope.require(directories, requestedStore);
        return reports.getObject().scheduling(BizContext.requireMerchantNo(), BizContext.requireStoreNo(),
                SecurityUtils.currentUserNo(), new SchedulingReportQuery(from, to));
    }
}
