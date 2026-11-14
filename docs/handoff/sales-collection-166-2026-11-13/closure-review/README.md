# 166 测试核款与资料后补验收

2026-11-13，已完成：恢复运营登录 → 发布核款与资料后补 → 测试门店核款 → App 权益回读。

| 节点 | 验证结果 |
|---|---|
| 登录 | 原因是一条自有测试店的门店同步旧事件未被统一投递接管。按校验摘要审核接管，未删除事件、未关闭启动校验；公网正常登录及页面加载通过，无脚本错误 |
| 测试核款 | 门店 ST261113Y693VWBMKT，年付单 01fc24ae8f2a40779243fe6b0ec40654。仅一次 TEST 确认，初始附件为空；付款 PAID，交付 COMPLETED |
| 后补资料 | 独立补资料会话追加测试图片与说明，资料状态由待补变完整；没有再次调用 confirmations，原资金字段保持 |
| 资金 | PAY / SUCCESS / TWD 199000，原凭据 mode=TEST；真实 cash-receipts 为空。资金权威拒绝 TEST 普通退款与真实出款 |
| 权益 | 仅一个 ORDER 来源的 PRO 购买周期，365天，到期 2027-11-13 22:36:23 CST |
| App | 模拟器5560正常166包、指定测试店；方案页显示“目前方案：零售專業版 · 剩餘364天23小時”，正式月/年价格与划线价可见 |
| 回归 | MySQL9后端34项、OPS87项、类型检查、生产构建及产物检查通过；端点契约检查无新增缺口 |

证据：production-browser-verification.json、final-readback.json、paid-period-verification.json、login-recovery.json、test-counts.json；截图见同目录 PNG。

## 发布与回退

- 源码：01c5f665b，包含 c384c378e 实现及独立 review 修复；已推送 main 与 codex/sales-closure-hardening-20261113。
- 后端基线 7095f47e…，仅合入本任务37个类，其他归档内容字节不变；发布 SHA256 为 0ed89ae0f55506150165418c36c0eec4ccb6c4a6715ca492cf11755b8a6cbf0b。
- OPS 基于当前线上源码 df0f6bf3e，三方合入本任务变更，保留并行发布的商户表单等功能。组合来源与文件摘要见 release.json。
- 新配置仅 COMMERCE_MALL_OFFLINE_TEST_STORES=ST261113Y693VWBMKT。无新数据库迁移；银行、销售条款、渠道开关及权限保持。独立 near-pay 未部署。
- 远端同一时点回退快照：/home/ume/sales-closure-20261113/before-release。恢复前持有部署锁并核对后续并行发布；两个 SHOP 同停，原 inode 恢复制品和容器配置，静态链接恢复。不得回滚资金、订单、迁移或直接撤销权益；回退代码仍须保留 TEST 退款拒绝修复。

## 证据边界

- 旧 OPS subscription.sources 是独立授予来源，不以其数组长度推断商城购买次数；本单唯一性以商城 ORDER/PRO 购买周期及履约任务为准。App 组合读口已显示 PRO 和年付剩余时间。
- 浏览器调试缓存曾无法读取上传响应，后续以仅补资料会话恢复；没有重提收款确认。
- 未发生真实收钱。本次是明确标记的测试闭环；旧运营月付单保持 CLOSED。真实销售实收、运营代购实收路径及正式签名 App 发布仍分别保留待验收状态。
- 独立 review 无 Critical；测试款退款隔离、真实到账时间填写等重要问题已修复并验证。无附件核款未知响应按原件恢复，后补重试不重复权益。
