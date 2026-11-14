# TDD-App统一事件合同

状态：T01 合同已实现，账号 SSE 运行时未实现。日期：2026-11-14。档位：1（文档、JSON合同与测试，无运行端点或数据库变更）。
依据：用户已确认的统一经营体验与消息方案，以及商户主数据与门店需求；Flutter 计划 T01。

## §0 需求到设计
| AC | 需求 | 落点 |
|---|---|---|
| AC1 | 前台经营实体对应平台商户且不混淆旧组织模型 | docs/api/经营实体映射与统一消息合同.md |
| AC2 | 所有业务统一消息格式，id/功能/对象独立 | app-events-v2.schema.json、openapi |
| AC3 | 定义事件处理，重复消息不能触发业务写操作 | app-events-v2-registry.json（18规则） |
| AC4 | 保留现有v1接口，v2不得宣称已部署 | prospective标记和标准文档附录 |

## §1 现状影响
核对 OperatingEntityModels/Service、StoreCreationAccessService、HostStoreCreationAuthority 和旧支付 SSE。entityNo 与 operatingEntityId 非同义。只新增候选合同和校验工具，当前 Controller、权限、交易、迁移与 v1 不变。

## §2 实现与对账
| 模块 | 产物 | 状态 |
|---|---|---|
| 身份映射 | docs/api/经营实体映射与统一消息合同.md | 明确已核对字段及权限 |
| v2合同 | docs/api/app-events-v2.openapi.json、app-events-v2.schema.json | 未实现端点仅作为未来合同 |
| 事件规则 | docs/api/app-events-v2-registry.json | 18个规则，限定客户端读/展示行为 |
| 校验工具 | tooling/check_app_event_contract.py | JSON Schema校验与App镜像一致性 |
| 文档索引 | 标准API文档附录、openapi-b-onboarding x-prospective-contracts | 不改旧接口响应 |

## §5 实现到需求与验证
- AC1：源码与现有DTO逐项对账，保留两种独立身份；暂无数据库变更。
- AC2/3：check_app_event_contract.py 校验18个事件样例、支付正例、5个非法用例、规则动作白名单及镜像一致性；Flutter shared_contracts 另验证金额/资源/未知事件。
- AC4：OpenAPI字段 x-implementation-status=not-implemented，旧v1文件未改。
执行：`python3 tooling/check_app_event_contract.py --mirror /tmp/codex-payment-sandbox-reads`。输出留在本轮执行记录。
偏差：T01未新增Java运行时校验器，避免在端点尚未实施时新建平行Java消息域。JSON Schema使用现有本机jsonschema验证；Java集成校验随T05服务端实现加入。本轮不运行无关全量后端构建。
