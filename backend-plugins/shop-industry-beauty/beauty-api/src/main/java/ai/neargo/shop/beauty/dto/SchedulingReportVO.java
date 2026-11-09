package ai.neargo.shop.beauty.dto;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * 服务业报表首期（BAPI-119 子集，TDD-服务业报表）：按预约开始时间落在窗口内计数，不含金额。
 *
 * @param appointments  预约数：除占位（HELD）以外的全部预约
 * @param arrivals      到店数：CHECKED_IN
 * @param arrivalRateBp 到店率（万分比）= 到店 ÷（预约 − 取消）；分母为 0 时为 null
 * @param byPersonnel   到店预约里，各人员在当前安排中被指派的项目数，按次数降序、人员号升序
 * @param topServices   到店预约的项目按规格计数，前 5，按次数降序、规格号升序
 */
public record SchedulingReportVO(OffsetDateTime from, OffsetDateTime to, long appointments, long arrivals,
        Integer arrivalRateBp, long noShows, long cancellations, List<PersonnelCount> byPersonnel,
        List<ServiceCount> topServices) {
    public SchedulingReportVO {
        byPersonnel = List.copyOf(byPersonnel);
        topServices = List.copyOf(topServices);
    }

    public record PersonnelCount(String personnelNo, long services) { }

    public record ServiceCount(String skuNo, long services) { }
}
