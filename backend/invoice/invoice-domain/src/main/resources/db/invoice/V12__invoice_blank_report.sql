-- 期末空白未使用字轨上报（TDD-发票-加值中心模板-鲸跃与汎宇 §4，汎宇 E0402）：
-- 期别结束后，号段里配到却没用掉的号码（next_no..end_no）要报给加值中心转财政部。
-- blank_reported_at 记成功时间，同一段只报一次；失败原因留在 blank_report_error，巡检下轮重试。

ALTER TABLE ivc_number_range
  ADD COLUMN blank_reported_at DATETIME NULL COMMENT '期末空白未使用号码已上报服务商的时间；NULL = 未报',
  ADD COLUMN blank_report_error VARCHAR(255) NULL COMMENT '最近一次空白上报失败原因；成功后清空';
