# 经营实体资料校验原因透传

状态：已实现（2026-11-16）。档位：契约变更（`/biz/entities/{e}/**` 转发 PAY 时 10400 的信封 `data`），无迁移、无配置。关联：near-pay `TDD-支付服务-I4进件与收款商户.md`「TW_ENTITY_2 资料格式」增量。

## §0 需求与验收

| 验收 | 实现 | 验证 |
|---|---|---|
| PAY 拒绝资料时 App 能指出是哪个字段、为什么 | PAY 400 带 `X-Internal-Error-Field`／`X-Internal-Error-Reason`；`EntityProfileRemoteCalls` 转为 `BizDetailedException`，信封 `{code:10400, data:{field, reason}}` | `EntityProfileRemoteCallsTest#rejectedFieldBecomesDetailsOnBadRequest` |
| 只透传字段键与原因，绝不透传取值 | svc-client 只收 `[A-Za-z][A-Za-z0-9_.]{0,63}` 与 `[A-Z][A-Z_]{0,31}`，其余丢弃；异常消息不含二者 | `ServiceClientsTest#rejectedFieldKeyAndReasonAreKeptOnlyWhenTheyAreSafeShapes` |
| 没有字段信息或非 400 时行为不变 | 原状态码映射 | `#withoutAFieldOrOnOtherStatusesTheMappingIsUnchanged` |

## §1 契约

- 适用：经 `EntityProfileRemoteCalls` 转发 PAY 的实体资料端点（`profile-draft`、`review-submissions`、`revisions`、`channel-requests` 等）。
- `reason` 取值：`UNSUPPORTED_FIELD`（模板没有这个键）、`INVALID_OPTION`（不在选项内，如 `issuePlace` 不是蓝新代码）、`INVALID_VALUE`（超长等）、`MISSING`（提交时缺项，`field` 为第一个缺项；完整缺项清单仍看资料视图的 `missingFields`）。
- 旧客户端：仍是 10400，只是多了 `data`，忽略即可。

## §2 实施

1. svc-client：`ServiceCallException` 增 `remoteErrorField`/`remoteErrorReason`（新增构造器，旧构造器不变）；`ServiceClients` 读两头并按格式过滤。
2. shop-base：`BizDetailedException`（`details` 不可含提交值）；shop-base-auth `GlobalExceptionHandler#onBizDetailed` 沿用 `onBiz` 的日志并返回 `data`。
3. shop-app：`EntityProfileRemoteCalls` 400 且有字段时抛 `BizDetailedException`。

## §5 验证

- `mvn -o -pl shop-app -am -Dtest=EntityProfileRemoteCallsTest,ServiceClientsTest,ApiResponseWrapperTest,HandlerMethodValidationApiTest,ApiResourceResponseTest,ArchitectureTest test`：2/2、13/13、8/8、2/2、1/1 通过；ArchitectureTest 22/23，失败项 `domainsMustNotTouchWebRuntime`（`OpsPlatformController.logout`）为既有问题，见 TDD-经营实体证件识别 §5。
