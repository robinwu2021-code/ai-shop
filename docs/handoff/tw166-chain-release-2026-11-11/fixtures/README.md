# 166 餐飲入駐測試資料

目标门店 ST2611100C2XNFCXRS，主体 M261106BF7GK8052W。onboarding-test-input.json 提供表单数据。onboarding-test-proof.png 是带 TEST FIXTURE ONLY 标记的上传测试图。统一编号与联系号码是测试占位值。

测试提交前，确认 166 主数据已配置餐饮行业与企业主体。上传 PNG 到商户端 QUAL 用途，用返回的 assetKey 替换 UPLOAD_REQUIRED。由测试商户本人核对协议并提交。

166 已添加 CATERING 与 ENTERPRISE 测试主数据。2026-11-11 已通过商户端上传测试 PNG 并提交原主体，申请号 MA261111RE680NW3YB。API 与数据库回读均为 PENDING，原主体仍为 PENDING_LICENSE。测试提交经过正常运营通知流程。

当前自动化运营身份只有 NP_IT_CONFIG / NP_IT_REVIEW 权限，缺少 merchant:apply:audit，也缺少运营权限管理能力。正式审核需要有该权限的 166 运营会话。

2026-11-11 后续验收：已使用正式运营账号受理并审核通过，原申请 APPROVED，原主体原位 ACTIVE；测试服务范围为 CITY。完整订单与法人绑定验收见上级 README 和 acceptance-166.json。
