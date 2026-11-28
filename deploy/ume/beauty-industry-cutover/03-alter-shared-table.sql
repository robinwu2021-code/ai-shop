-- 与餐饮共用的指派表：美业服务参与（B-4）补的两列与对象类型注释（TDD-美业域迁移新库 B-6）。由 gen.py 生成，不要手改。
-- run.sh 只在两列都还没有时执行；数据保留。
ALTER TABLE `ind_assignment`
  MODIFY COLUMN `target_type` varchar(24) DEFAULT NULL COMMENT '指派对象类型：VISIT 到店单、ITEM 订单行、UNIT 履约单元、APPOINTMENT_ITEM 预约项目行、TASK 美业服务任务',
  ADD COLUMN `employment_assignment_no` varchar(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin DEFAULT NULL COMMENT '人员当时有效的门店任职号（美业服务参与）' AFTER `deleted`,
  ADD COLUMN `planned_assignment_no` varchar(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin DEFAULT NULL COMMENT '对应的预约计划安排号（美业服务参与）' AFTER `employment_assignment_no`;
