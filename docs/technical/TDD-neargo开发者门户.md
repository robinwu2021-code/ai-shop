# neargo 开发者门户技术设计

状态：已实现并部署 UME（https://nearshop.umeshop.ai） · 上游：[PRD](../requirements/PRD-neargo开发者门户.md)

## §0 需求对账
| AC | 落点 | 验证 |
|---|---|---|
| AC1 | Raneto 动态文件渲染 | live Markdown HTTP test |
| AC2 | 文件导航与链接适配 | nested Chinese navigation HTTP test |
| AC3 | SSR 模板、站点元信息 | SEO HTTP test |
| AC4 | 自有轻量主题、搜索 | search HTTP test 与浏览器检查 |
| AC5 | 独立应用及部署文件 | npm test、启动检查 |
| AC6 | locales、/en、content-en 与共享模板 | locales.test、双语浏览器检查 |

## §1 架构
独立 developer-portal，锁定 raneto 0.18.1，Node 24+。复用其 Markdown 渲染、清洗和文件内容模型，使用自有 Mustache/CSS 主题，无前端构建与数据库。文档发布目录可挂载。程序修改才重新部署，内容更新直接复制。

## §2 实施计划
1. 在 developer-portal/test 写 HTTP 验收：运行期间新增、编辑、删除；嵌套目录；中文和相对链接；SSR 元信息；搜索；404。
2. 新建独立 package/lock、app/server、主题与初始 content，不修改根 workspace 或其他会话文件。
3. 提供 Dockerfile、compose、README，记录内容挂载和域名配置。
4. 统一执行相关测试和桌面/手机检查，修复只验证受影响部分。

配置：CONTENT_DIR 指向公开目录；SITE_URL 为公开 origin；PORT/ADDRESS 监听地址。公开阅读，关闭编辑和登录。不涉及资金、库表或幂等动作。

## §5 验证记录
2026-11-12：在 developer-portal 执行 npm test：1 个 HTTP 集成用例通过，覆盖运行中复制/修改/删除、中文三级路径、嵌套菜单、相对链接实际请求、图片、HTML 正文/元信息、动态 sitemap、robots、中文子串搜索、带点号版本文件路径、HTTP sitemap 协议/首页规范化及 404。Node v26.0.0。

浏览器检查：桌面及 390×844 手机视口，目录、正文、表格与页内目录显示正常。docker compose config --quiet 通过；本机 Docker daemon 未运行，因此未构建或启动容器。未运行无关后端/三端测试。

初次复现并修正：macOS /var 临时目录 realpath 不一致导致上游拒绝；Mustache 递归 partial 继承父上下文导致无限递归；导航各节点显式归一布尔状态和子列表。上游 Lunr 未启用中文分词，因此在搜索结果边界使用直接子串扫描，不引入索引服务。

限制：上游逐请求扫描目录，未做并发/规模压测；普通文档 HTML 使用 no-cache。初始四业务页面为概览，完整第三方 API 正文未批量迁入。

## §6 实现对账
本次新建 developer-portal 31 文件（应用/锁文件、HTTP 测试、主题模板/静态资源、6 份公开 MD、5 份目录元信息及部署说明）与本 PRD/TDD/ADR。独立门户不属于 b-app/c-app/ops-web 三端路由清单。共享工作区不提交其他会话文件。

评审：独立代码评审发现版本文件名含点号被上游路由截断及 sitemap 固定 HTTPS 两项，已适配并通过新增 HTTP 断言。修复仅影响服务端路由/SEO，不重复运行未变化的界面检查。

## §7 主题整改（2026-11-12 用户追加）

档位 1：新增浏览器本地外观偏好交互，不变更业务接口、权限或部署配置。原主题硬编码绿色覆盖背景、文字及代码区，缺少统一基础色和强调色边界；本次统一 CSS 语义变量。

计划与落点：portal.css 定义黑白灰基础色及五套强调色、明暗模式；header.html 增加原生主题选择器；theme.js 在首屏样式前恢复本地偏好并响应系统外观；brand.svg 使用中性色；README 维护主题清单；theme.test.mjs 验证跨页面恢复、系统变化、无效和受限存储。现有三端 UI 索引不包含此独立门户。

验收：背景/正文/边框/代码以中性色为主，强调色仅用于交互；默认极简黑，提供蓝/紫/绿/橙；电脑和手机可用；保持无内容构建流程。验证：npm test 3/3 通过（含原 HTTP 验收及两项主题行为测试）；浏览器验证五套主题的浅/深色共十种组合，390px 视口均无横向溢出；深色紫主题刷新后偏好恢复正常，桌面和手机布局检查通过。修正品牌 span 继承旧样式造成标识对比度问题。预览恢复跟随系统与默认极简黑。

## §8 Near Pay 文档同步（2026-11-12）

档位 1。来源：near-pay 聊天「完善 2.0 架构与代码」的 docs/developer；源文件当前未提交，不以仓库 HEAD 冒充文档版本。保持支付业务语义及源码不动。

计划/实现：scripts/sync-nearpay-docs.mjs 白名单复制五个公开文件，仅补充站点元信息；app.mjs 提供三个已发布示例的附件下载；替换支付接入概览并修正快速开始发布范围；长文页内目录限制视口高度以便滚动。支付完整指南保留单页及原编号，避免切分导致章节引用失效。

验证：payment-docs.test.mjs 检查代码围栏、单一 H1、JSON 示例、相对链接及真实 HTML/下载响应；原样复制的 Python、Node.js 示例只执行离线自检，不调用支付系统。具体结果见本节后续记录。

本轮格式 review 结论：源指南结构可直接发布（10 章、19 表、20 代码块）；代码围栏闭合、单一正文 H1、JSON 示例解析及相对链接检查通过。站点补充元信息，下载说明移除面向发布者的编辑提示，隐藏无页面的 samples 空目录菜单。指南正文与源文件完全一致（三个示例逐字节一致）。未对全部支付业务实现进行重复审计。

验证结果：npm test 5/5 通过；补充菜单断言后仅重跑 payment-docs.test.mjs，2/2 通过。Python P1/P2 与 Node.js P3/P4 离线自检通过。浏览器确认桌面目录37个锚点可滚动、手机390px页面无横向溢出、表格可独立横向滚动，20个复制按钮正常生成。本地预览已更新，未部署生产。

来源快照（2026-11-12；源 docs/developer 尚未提交；以下为源文件 SHA-256）：

| 文件 | SHA-256 |
| --- | --- |
| README.md | `dbe23902c7651328f251828ab939957bb734a1c8bd68965593f111de86e46a12` |
| 外部API接入指南.md | `bdefef789b1eb47f75c346f81df67c0eba3a01a274944798eb8f5e56e0942ea1` |
| samples/open_api.py | `3d478063bce5e2a1ed043ccfff5d4916bc446656e544e793acf1fdad22007cc3` |
| samples/crypto.mjs | `0e25da3c94356a2e0096ee8ec0a6b045cfcc48342b5fe49d0dbd1450cc0b113a` |
| samples/test-vectors.json | `75822bb277f14b618d9e653c7b34aff3420ef0f03ce7e6710478c89305d3c6a4` |

## §9 中英文（用户追加）

档位 1：新增 /en 文档路径及英文内容目录 EN_CONTENT_DIR。中文原网址不变，英文 content-en 独立目录，由相同 Raneto 渲染逻辑读取，避免中文内容混入英文搜索/导航/sitemap。locale 字典集中维护界面文案；pagePairs 登记对应文章，无译文则切换目标语言首页。SEO输出独立canonical、html lang与双向hreflang，robots列出两语言sitemap。

实施：app/locales/server、共享模板及复制按钮文案；新增8份英文文档和目录meta；compose挂载英文目录；locales.test覆盖配对页面、导航链接、查询、下载、SEO、404及支付JSON示例一致性。保持现有样式、业务合同和Markdown无构建发布方式。中文同步命令不覆盖英文翻译，后续源文档更新由维护者复核英文内容。

双语验证记录：npm test 7/7通过；双语测试实际遍历8对页面和英文正文相对链接、下载，验证双向切换URL、语言/标题/界面、canonical/hreflang、搜索、英文sitemap和404。英文支付文档保留中文对应的10章、20代码块，所有JSON示例与中文逐对象相等。代码中的说明性注释已英译，API标识和JSON示例数据保留原值。Docker compose config --quiet通过。

浏览器：从中文支付指南点击EN进入英文对应文章，检查英文导航、主题菜单、搜索及Copy文案；390px视口无横向溢出，移动目录正常折叠；点击中文返回原支付指南。无需重新验证未改动的支付算法示例。未生产部署。

## §10 UME 生产部署（用户授权 nearshop.umeshop.ai）

档位1：独立容器与Nginx部署配置。域名现已指向35.194.228.47（.201入口），证书覆盖该域名，至2026-12-24 UTC有效。现有 nearshop 根路径跳转ops-shop，部署仅替换.166该vhost根路径和fallback为文档代理，原ops-shop/shop-api/oms/h5等locations保留，.201不修改。

应用使用.166 Docker host网络，监听127.0.0.1:3210；256MiB容器上限，0.5 CPU。代码镜像与两套只读挂载文档目录分离。增加TRUST_PROXY显式信任loopback及.201，确保上游限流按真实客户端而非所有访客共用代理IP；proxy.test验证不同转发客户端各自计数。

部署前8项本地测试通过；复用此前双语、下载与界面证据。远端Node24容器已验证可运行，现有TLS域名匹配。先构建并检查容器，再备份Nginx、测试配置、reload，最后公网验证。

生产部署结果：release 20261112-bilingual-portal，镜像sha256:b4d9fb31138f9765e32abfd51800729e73301ffd69f7c067bdb99545b149da3c；容器running，restart=0。nginx -t通过并reload；首页不再跳ops-shop，原ops-shop和oms仍200。公网15项检查通过，三个示例下载与源文件字节一致；浏览器生产中英文切换通过。SITE_URL及双语canonical/hreflang已使用正式域名。

打包时macOS资源叉文件随tar进入内容目录，目录检查发现后已清除本次生成的._文件，未重启容器；复核两个动态sitemap各8个正式页面且不含元数据。以后打包排除资源叉。来源hash、公开检查、部署目录及回退配置见 [交付证据](../handoff/developer-portal-ume-2026-11-12/public-verification.json) 和 [UME维护说明](../../developer-portal/deploy/ume/README.md)。
