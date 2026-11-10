# 166经营主体与支付资料修订发布

日期：2026-11-10。用户授权先部署已完成功能，再提交远端；范围为本批基础能力后端，不含其他会话服务业或蓝新运行时增量。发布分支为`codex/foundation-profile-166-20261110`，两仓独立发布目录，未覆盖原共享目录或暂存区。

## 发布范围

- ai-shop：账户侧经营主体目录/上下文/创建/原键回执、可选本人已审核法定主体引用，V648；宿主转发独立支付商户创建和资料修订六个方法/路径。
- 独立near-pay：V81经营主体所有权回查、PM建档与首个typed成员同事务；V82资料修订永久原键回执、期望修订号并发控制、审核回显；复用既有资料和审批，待审期间旧批准版本继续生效；BIZ敏感日志整体省略。
- `pay_merchant_creation`为共享身份与创建回执，已加入EnvironmentTablePolicy；金额、订单、账务等金融表仍按原规则隔离。
- 库存、OPS不重启、不替换；N1和库存writer开关保持原前置。App未发包，真实渠道审核/绑定/收款不因这次部署视为完成。

## 验证与发布记录

[制品及边界](./artifacts.json)、[定向构建验证](./build-verification.json)、[生产迁移](./migration-production.json)、[演练最终记录](./migration-rehearsal-final.json)。源码先完成独立候选选择和验证，再部署同一制品，随后提交；提交号见本目录最终提交记录。

演练修正了发布工具对嵌套pay-domain迁移资源和Java历史迁移类的提取，未修改历史SQL或修复校验值。首次生产切换被环境表分类守卫拦截，已回退制品；当时新回执表0行，临时改为非pay_前缀恢复旧PAY启动。之后补共享表分类与真实MySQL启动守卫回归，修复构建通过，恢复原表名，顺序PAY→API重发。生产V648/V81/V82已成功应用，不重复迁移。

## 回退约束

原始制品和配置备份位置见artifacts。旧PAY启动会拒绝未识别的`pay_merchant_creation`，不能只回退JAR：先停止新PAY，在不删除回执数据的前提下，将表临时改名为`foundation_pending_merchant_creation_<发布suffix>`，再启动旧PAY。回到新PAY前恢复原表名。V82 nullable字段保留，禁止为回退删除业务资料或改写已成功的Flyway历史。API与PAY运行制品应按同一发布记录恢复；OPS和库存保持原状。

本批只读业务探测和实际运行制品核对记录在部署成功后附入，不把未鉴权401或应用健康等同于真实收款闭环。正式HTTPS/真机、新注册和短信邮件送达、指定PM资料批准/渠道正式送审仍需专项验收。

发布配置补齐：独立PAY保留`ops`并启用`api` profile，确保BIZ控制器实际注册；为已有四个白名单typed身份按166可信默认PRODUCTION增加执行环境路由绑定，未新增商户成员、角色或关闭环境隔离。身份与凭据不写入此记录。

最终[运行制品与健康核对](./runtime-verification.json)和[真实身份只读探测](./authenticated-smoke.json)通过：V648/V81/V82成功，原回执表已恢复、临时兼容表0张；API/PAY容器内JAR摘要与发布制品一致。经营主体目录、PAY创建上下文、成员目录可用；员工canCreate=false、未授权商户拒绝，测试会话已注销；未创建营业对象或执行付款。

最终[运行制品与健康核对](./runtime-verification.json)和[真实身份只读探测](./authenticated-smoke.json)通过：V648/V81/V82成功，原回执表已恢复、临时兼容表0张；API/PAY容器内JAR摘要与发布制品一致。经营主体目录、PAY创建上下文、成员目录可用；员工canCreate=false、未授权商户拒绝，测试会话已注销；未创建营业对象或执行付款。


提交前补齐经营主体Service接口/impl分包及新路由权限台账，宿主定向构建/测试通过后只重发API；PAY未因该结构调整重发。[架构边界记录](./architecture-boundary.json)明确保留原基线失败，不宣称全量架构测试全绿。迁移临时账号及两份演练库已[清理](./cleanup.json)，生产数据及原始备份保留。

提交追踪见[两仓提交记录](./commits.json)。补充35条真实Handler/公开OpenAPI/外部Flutter消费者的交付登记，按既有分阶段机制说明本仓Vue不直接调用，未增加历史欠账；原有两条评价读取路由仅登记已有biz:review保护，不改业务代码，BizEndpointPermTest十二例通过。
