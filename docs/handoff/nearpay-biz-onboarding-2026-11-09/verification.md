# 本机验收记录与复现

2026-11-09（Asia/Shanghai）。此记录不覆盖真实 ai-shop / 蓝新 / MySQL / 物理设备，也不是生产验收。所有宿主身份为合成数据，临时服务仅监听 127.0.0.1；验收后已停止。

## 已取得的证据

| 检查 | 结果 | 日志 |
|---|---|---|
| JDK21 pay-svc 及全部上游 `verify` | BUILD SUCCESS；3715 项，3366 通过、349 条件跳过，0 失败/错误 | `/tmp/nearpay-biz-final-verify.log` |
| 新增身份/转发/HTTP 用例 | 3 + 2 + 1 项纳入上述全量；USER/MCH_ACCOUNT 同 ref 隔离、撤销、角色、跨商户、OPS冻结与补件通过 | `/tmp/nearpay-biz-http-green.log`、`/tmp/nearpay-biz-review-green.log` |
| 实际 Dart HTTP 网络客户端 | 1/1 通过；经生产 Java 转发适配器，2 MiB 上传/同命令重放、slot 10、二进制逐字节读取、确认/原结果查询、退出围栏 | `/tmp/bapp-biz-real-http.log` |
| 新页面/客户端及本地化定向 | 13/13 通过（9 功能 + 4 本地化） | `/tmp/bapp-biz-l10n-green.log` |
| Flutter App + foundation 全量 | 863 通过、7 条件跳过，0 失败 | `/tmp/bapp-biz-full-test-final.log` |
| scoped analyze | No issues found | `/tmp/bapp-biz-analyze-final.log` |
| Flutter release web | 构建成功；52.6 秒；实际配置 MERCHANT_API_BASE_URL + 功能开关 | `/tmp/bapp-biz-web-build-final.log` |

Maven 按本次运行各模块 Results 汇总，未叠加历史 XML。Flutter 全量中的 HTTP 验收测试默认跳过，本批另用 origin 显式启用并通过。SDK 使用仓库 `.toolchains/flutter`（Dart3.13），旧全局 Flutter 不满足本仓库版本要求。测试 cwd 必须在 `apps/merchant_app`，否则缺 App/设计系统资源会产生无效失败。

## 复现命令

在 near-pay 根目录，JDK21，MAVEN_OPTS 可设 `-Xmx640m -XX:ActiveProcessorCount=2`：

```sh
mvn -o -f backend/pom.xml -pl pay/pay-svc -am verify
```

需要 Dart HTTP 夹具时，先完成上述编译；从刚产生的 `backend/pay/pay-svc/target/surefire-reports/TEST-ai.neargo.shop.pay.svc.BizOnboardingHttpAcceptanceTest.xml` 读取 `java.class.path`，以此 classpath 运行 `ai.neargo.shop.pay.svc.BizOnboardingHttpLauncher`，加 JVM 参数 `-Dnearpay.biz.http.acceptance=true`。不得与 Maven 编译 target 同时启动。夹具随机端口，等待日志 `BIZ_DART_HTTP_READY http://127.0.0.1:<port>`；使用该 origin 替换下方端口。该 launcher 在测试源码，不能在服务部署配置中启用。

在 ai-shop-bapp 的 `apps/merchant_app` 目录：

```sh
../../.toolchains/flutter/bin/flutter test test/integration/nearpay_biz_onboarding_http_test.dart --dart-define=NEARPAY_BIZ_HTTP_ORIGIN=http://127.0.0.1:<port>
../../.toolchains/flutter/bin/flutter test test ../../packages/foundation/test
../../.toolchains/flutter/bin/flutter analyze lib/features/payment_onboarding lib/features/merchant/store_payment_page.dart lib/main_prod.dart ../../packages/foundation/lib/src/network.dart
../../.toolchains/flutter/bin/flutter build web --release --dart-define=MERCHANT_API_BASE_URL=https://ai-shop.test --dart-define=NEARPAY_BIZ_ONBOARDING_ENABLED=true --output=build/nearpay-onboarding-verify
```

每次 HTTP 测试用全新夹具/H2，结束停止本机进程。构建域名 `.test` 仅为编译占位，不执行部署。真实 ai-shop 配置尚待接收方实现，故 App 开关继续默认 false。

## 审查与回归

完成一次独立只读审查，Critical 0；重要问题和重分级项均完成失败复现→修复→通过：

- 明确 seal/save 失败清空输入后会丢未保存状态：增加两个 Widget RED 用例，修复后保留 dirty，重新填敏感值或重新加载后才能确认。
- 身份服务 302 携合法 JSON 可被当有效身份：定向 RED，显式拒绝任何 3xx；不因禁止跟随重定向而假设响应本身不可接受。
- 图片解码缓存未随预览关闭/身份切换清理：两个实际 ImageProvider cache 状态 RED，用同一 provider evict 后 GREEN。
- 单申请看不到平台/渠道状态和驳回原因：从 Minor 重分为 Important（用户无法判断下一动作），Widget RED→GREEN，新增单申请状态读取与展示。

非阻塞后续：App 历史补件列表尚未展示，当前补件与原因已显示，历史可在运营端查看。真实 ai-shop、渠道、MySQL、App PDF、未做物理设备/浏览器新页截图验收均属明确交付边界。共享 foundation 二进制能力已由全量既有 JSON/错误/会话测试回归。

本地化门禁首次完整运行暴露新增硬编码，已迁至 ARB 后重跑；未增加硬编码基线。转发重放测试最初比较时间戳全部 metadata 受 SQL 毫秒精度影响，已改为核验文件身份 fileNo，并独立逐字节比对下载；产品无相关变更。

## 文档检查边界

ai-shop `npm exec -w @ai-shop/shared -- vitest run tests/doc-standard.test.ts`：6/8 通过、2 项存量失败。失败涉及既有 `TDD-平台运营页面.md`、`TDD-支付运营端与统一组件库.md` 缺索引，以及 `docs/technical/design` 无状态行 42 篇超过基线 27；本包不在该统计范围，本轮未改这些文件。保留现状，未把全仓文档门禁宣称为通过。交接包全部 Markdown 本地链接、SVG title/desc、JSON/YAML 解析、字段与路由计数及两份副本一致性另行校验通过。near-pay 全局 diff 空白检查还有既有生成 CSV 的 CRLF 提示，本批限定文件检查无新增问题。
