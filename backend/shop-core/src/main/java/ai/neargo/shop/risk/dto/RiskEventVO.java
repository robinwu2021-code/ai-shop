package ai.neargo.shop.risk.dto;

import java.util.List;

/**
 * 风险事件（对应 ops-web {@code lib/types/risk.ts} 的 {@code RiskEvent}）。
 *
 * @param signals     命中信号。**必须是数组且非 null** —— 页面直接 {@code .map()}，
 *                    给 null 是整页白屏，且控制台之外没有任何提示
 * @param refs        证据单号，同上
 * @param subjectName 契约之外的附加字段：{@code subject} 存的是标识（userNo / 设备号），
 *                    运营列表要显示人话。前端不读也不会坏
 */
public record RiskEventVO(String eventNo,
                          String type,
                          String subject,
                          String subjectType,
                          String subjectName,
                          List<String> signals,
                          List<String> refs,
                          String status,
                          String createdAt,
                          String verdict) {
}
