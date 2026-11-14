## 2026-11-14 真机沙箱验收结果

用户明确同意本轮两笔 NT$1 官方蓝新沙箱付款及条款，已使用 2/2；不使用真实卡，不发生真实扣款。目标为 robin 的创势沙箱验收店，设备为 REDMI 2510DRK44C。

| 场景 | 支付单 | 结果 |
|---|---|---|
| 前台 | PYO261114112637264 | 真实 HTTP/SSE、原生 started=1/completed=1，对应消息未读 |
| 常规包后台后恢复 | PYO261114114049265 | 未读 4→5；同一进程的原生 started/completed 均为 0，历史收款未补播 |

后台活动日志记录 11:44:34 进入后台、11:44:48 付款成功、11:45:04 恢复前台。正常进程 PID 27163 保持不变；03:43:26Z 基线与 03:51:04Z 只读原生状态均为 0，覆盖恢复后 30 秒。通过 debug VM 调用已有 MethodChannel `status`，未修改计数、业务状态或凭据；付款期间已断开调试连接。窗口单帧未识别 launcher，后台事实以活动 pause/stop/resume 日志为准。

两单均为 SANDBOX/SUCCEEDED、paidMinor=100。各一条收款通知首次 AUTO 即 DELIVERED，HTTP 200（58ms/25ms）；APPLIED 与 SHOP receipt 的 hash/编号一致。USR 与关联 MCH 各一条授权消息和 stream，是两个收件人，不是重复付款。

真机常规收款页显示 TWD5／5 笔成功收款，完整列表还有待付款单；消息徽标和列表均为 5，未标记已读。常规 APK 已恢复，强制结束重启后用户、门店和 TWD5／5 笔仍恢复。

**自动化限制：** 两次管理式长驻集成夹具均未完整通过。后台后连接中断，随后系统记录 ADB 测试清理 force-stop；未找到原生崩溃证据，原 VM 断连根因尚未完全确定。不能把 `physical-live-closeout.log` 标为全套 PASS。前台证据来自真实夹具；后台业务场景由常规 APK、只读原生计数、活动日志及真实消息/订单独立验证。后续仍需完善该机型的长后台自动化运行方式。

详见 `physical-final-result.json`、`physical-normal-speech-*.json`、`physical-*-db-proof.json`、`physical-background-lifecycle.log` 及实际截图。私有调试 URL、认证码和服务凭据不入库。本轮未增加产品代码、未调整后台安全限制；QoS1、JetStream、消费 ACK 与系统后台推送仍不在范围内。
