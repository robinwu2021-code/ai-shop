package ai.neargo.shop.beauty.service;

import ai.neargo.shop.beauty.dto.SchedulingReportQuery;
import ai.neargo.shop.beauty.dto.SchedulingReportVO;

/** 服务业报表（BAPI-119 首期子集）。读权限与预约列表相同：biz:booking:view 或 biz:booking:manage。 */
public interface SchedulingReportService {
    SchedulingReportVO scheduling(String entityNo, String storeNo, String actorNo, SchedulingReportQuery query);
}
