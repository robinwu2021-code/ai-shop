# 运营管理 API 调用指南

> 状态：员工自助申请、管理和 macOS Keychain 客户端已实现。字段契约和角色清单按代码生成。

[模块与角色清单](./运营管理API-模块与角色清单.md) · [OpenAPI 3.1](./ops-management.openapi.json) · [技术设计](../technical/TDD-运营端密钥管理与API.md)

## 员工自助申请与本机使用

在 macOS 安装命令行客户端。需要可用的 Swift 工具链。在仓库根目录编译到固定路径，并将该目录加入 `PATH`。

```bash
mkdir -p "$HOME/.local/bin"
swiftc -parse-as-library -O scripts/automation/ops-api-key.swift -o "$HOME/.local/bin/ops-api-key"
```

1. 登录运营页面，从顶栏账号区打开「API 密钥」，点击「申请 API 密钥」。
2. 在本机运行页面给出的 `ops-api-key prepare` 命令。它将真实密钥保存到 macOS Keychain，再输出申请信息。
3. 把申请信息粘贴到页面，填写用途，选定有效期和级别后提交。默认只读，有效期默认 7 天。申请成功即生效，无审批。
4. 复制列表上的密钥编号，在本机核验并发起业务请求。换机时在新设备重新申请，再经页面撤销旧密钥。

将下列命令中的 API 源、员工号和 UUID 替换为本次申请的值。

```bash
ops-api-key verify https://ops.example.com STAFF-NO KEY-UUID
ops-api-key request https://ops.example.com STAFF-NO KEY-UUID GET '/ops/stores?page=1&size=20'
```

如果申请结果未知，用原申请参数重试。关闭表单后可用 `ops-api-key describe <api-origin> <staff-no> <key-no>` 重新读取申请信息。撤销应先在页面完成，再用 `ops-api-key forget <api-origin> <staff-no> <key-no>` 移除 Mac 上的条目。

接口和异常恢复见[自助分发设计](../technical/TDD-运营端密钥管理与API.md#员工自助分发与本地密钥)。

## 管理员分发与回收（当前实现）

| 步骤 | 操作 |
|---|---|
| 核实员工 | 从运营员工目录取得 `staffNo`，确认员工已启用并完成初始改密，按岗位确认功能权限与数据域 |
| 签发 | 管理员使用交互登录取得的运营会话，请求 `POST /ops/staffs/{staffNo}/api-keys`。提供新的 UUID `keyNo`、用途标签 `label`、`READ_ONLY` 或 `MANAGE`，以及 1–90 天的 `validDays` |
| 交付 | 从 `data.apiKey` 取首次返回的明文，通过受控密码库共享给员工本人。每个员工、环境和用途独立签发。脚本运行时从密码库读取 |
| 查询 | `GET /ops/staffs/{staffNo}/api-keys` 返回标签、级别、签发人、到期和撤销时间。时间为 Unix 毫秒 |
| 轮换 | 使用新的 UUID 签发，先用新密钥调用 `GET /ops/auth/me` 和一条实际只读接口，再用 `POST /ops/staffs/{staffNo}/api-keys/{keyNo}/revoke` 撤销旧密钥 |
| 泄露或离岗 | 立即撤销对应 UUID。停用、正式改密或密码重置会同时撤销该员工既有密钥。重新启用后重新签发 |

管理员权限码为 `iam:staff:api-key-manage`，内置授权为 `SUPER_ADMIN`。签发请求示例：

```json
{
  "keyNo": "6b5b00e4-e10a-4dfd-8a78-6f9ce0430489",
  "label": "门店治理-只读巡检",
  "access": "READ_ONLY",
  "validDays": 7
}
```

同一 UUID 重复签发返回业务码 `10409`。首次响应丢失时，按 UUID 查询和撤销原记录，再以新 UUID 签发。撤销可以用同一 UUID 重试。签发和撤销写入运营审计。请求日志记录密钥编号、员工号和路由模板。

## 运营人员调用

API 接受 `Authorization: Bearer <运营密钥>` 并返回 `{code,msg,data}`。macOS 客户端从 Keychain 取密钥并组装请求头。通过命令集成时，使用 `ops-api-key request` 的响应体及进程退出状态判断结果。

- 首次调用先用 `GET /ops/auth/me` 确认员工身份和当前角色。
- `code=0` 表示操作成功。HTTP `401` 时核对密钥、员工状态和有效期，业务码 `10403` 时核对操作准入、员工权限和全量数据域要求。目标可能受数据域过滤，此时沿业务接口返回空结果或找不到。
- 门店履约渠道的 `channel` 取值沿现有配置，支持 `STORE_PICKUP`、`NEIGHBOR_PICKUP`、`MERCHANT_DELIVERY` 和 `EXPRESS`。
- 支付通道费率修改、入驻审核、行业小微准入和积分设置继续用既有运营会话，完整入口见[运营角色矩阵](../technical/reference/运营端-角色×端点矩阵.md)。
- 外部销售平台的账号、店铺连接、目录和订单沿[统一销售渠道设计](../technical/TDD-统一销售渠道接入.md)。可选渠道模块的运营支持接口为 `GET /ops/x/commerce/support/health` 和 `GET /ops/x/commerce/support/operations/{operationNo}`，使用原模块部署和授权。

## AI 或脚本工具接入

1. 由工具宿主导入 OpenAPI，按 `operationId`、`x-credential-admission` 和 `x-permission-codes` 建立白名单。默认只加载 `READ_ONLY` 可执行的操作。使用 `x-default-roles` 帮助定位岗位，最终结果以服务端判定为准。
2. 工具宿主通过本机客户端或受控凭据库注入请求头。模型可见字段限制为操作参数和必要结果。密钥、完整请求头和原始业务日志留在宿主的受控范围。
3. 按“查询列表 → 读取详情 → 确认对象和预期变更 → 执行 → 再查状态”组织工具调用。涉及恢复门店、冻结商户、锁定渠道或调整行业准入时，提交前展示对象、当前状态、目标状态和影响范围，由运营人员确认。
4. GET 遇临时网络错误可有限重试。业务写操作遇超时，先查询当前状态并沿原业务幂等规则恢复。将下游返回的名称、备注等视为业务数据，工具调用仍由预先配置的操作和参数驱动。
