# B端滑块验证码：Flutter 与 legacy Vue 交付跟踪

创建：2026-11-13。依据：[TDD-B端滑块验证码](TDD-B端滑块验证码.md)。公开契约：[biz-auth-captcha.openapi.json](../api/biz-auth-captcha.openapi.json)。

## 交付范围

| 调用端 | 当前状态 | 证据与边界 |
|---|---|---|
| 独立 `ai-shop-bapp` 仓库的商户 Flutter 应用 | 原生拼图及认证接线已实现，主代理负责其验证与应用包交付 | 原生 PNG 展示、原图坐标轨迹、异步 challenge/verify、刷新/取消及认证挑战重试属于该仓；本仓不伪造 Flutter 代码入口 |
| 本仓 legacy Vue `b-app` | 延后接入 | 当前没有 `/biz/auth/captcha/challenge`、`/biz/auth/captcha/verify` 的实际 UI/API 调用；本清单明确登记这两条待交付工作 |
| Java 后端 | 已实现且完成相关真实 HTTP/MySQL 校验 | `BizCaptchaController`、`BizCaptchaService`、V663；详细测试及统一分支移植记录见原 TDD |

`backend-ui-delivery-auth-captcha.json` 使用已有 `JAVA_BACKEND_UI_DEFERRED` 阶段，针对的 app 是本仓 Vue `b-app`。`consumerRepository=ai-shop-bapp` 说明当前实际调用端所在位置，不代表 Vue 已接入。清单要求真实 Handler、公开 OpenAPI 与此任务计划同时存在；没有增加历史孤儿基线、虚构 endpoints.ts 登记或放宽守卫。

## legacy Vue 后续任务

1. 在 Vue 的认证 API 层接入两条公开端点，并按公开契约映射 action、principal、PNG 尺寸与轨迹字段。
2. 实现适合浏览器的拼图交互：原图坐标换算、LTR 拼图坐标、DOWN/MOVE/UP 与相对毫秒；刷新使旧拖动失效，取消不提交认证。
3. 仅在服务端返回 `10452` 且 `data.captchaRequired=true`、action 对应当前请求时展示挑战；成功后携带 `X-Captcha-Token` 重试原操作一次，不增加通用网络重试。
4. 覆盖密码 login/identities、SMS/email sends；OTP 凭证登录和身份进入保持现有行为。日志不得记录 proof、完整轨迹或凭据。
5. 完成真实 API/UI 接线与相关测试后，从本清单删除已交付条目，再跑既有孤儿守卫；不得长期保留已接入条目。此阶段未承诺排期，也未在本轮实现。

## 发布范围

服务端开关默认关闭；开启后同样约束所有密码认证/发码调用方，不能靠缺少某个客户端头来豁免。Flutter 已实现不等于 legacy Vue 已兼容，发布者应按实际支持的客户端范围记录开关启用决定。部署授权和实际执行由本轮主代理负责，此交付清单不是部署完成证明。

## 本轮检查

只新增交付清单、公开契约和本文档。运行 `python3 scripts/tests/test-backend-ui-delivery.py` 与 `python3 scripts/check-app-backend-orphan.py --check`；结果：交付清单测试 `Ran 6 tests ... OK`（`/tmp/captcha-delivery-test.log`）；孤儿检查 `✓ 未超过基线`（`/tmp/captcha-delivery-orphan.log`）。不重复 Java/Flutter 已有效的验证，也不修改基线或守卫代码。
