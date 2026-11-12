# neargo 开发者门户

Raneto 0.18.1 + 自有轻量主题，服务端读取 Markdown，无数据库，无内容构建步骤。程序和主题首次安装后，发布内容只需复制文件。

## 本地运行

要求 Node.js 24+，npm 11.9+。此目录独立于根 workspace。

```sh
cd developer-portal
npm ci --workspaces=false
npm start
```

打开 http://127.0.0.1:3210 。验证：`npm test`。

## 发布文档

将公开 Markdown 及图片放入 `content/` 对应目录，例如 `content/支付/退款/创建退款.md` 对应 `/支付/退款/创建退款`。刷新后显示，无需重新构建或重启。删除文件后返回 404。

正文用普通 Markdown；标题默认读取首个一级标题。可选 YAML 元信息：

```yaml
---
Title: 创建退款
Description: 创建退款的参数与结果处理说明。
Sort: 10
---
```

文件夹可用可选 `meta` 文件指定 `Title`、`Sort`，否则用目录名。文档之间可用相对 `.md` 链接；图片可放在同目录的 images 中。文件名避免空格和 URL 保留字符 `# ? %`；中文及 v1.0.md 这类版本文件名支持。index.md 建议只用于站点首页，子目录需用具名文档。

`content/` 是公开发布内容的唯一真源，不要把整个内部 docs 目录挂载进来。支付已同步完整第三方接入指南与公开离线示例，其他行业当前为接入概览。本应用不提供账号申请、密钥管理或 API 在线调试。

## 部署

```sh
SITE_URL=https://你的开发者域名 docker compose up -d --build
```

首次构建安装程序。以后仅更新挂载的 content 文件，不再运行 build。外层 Nginx 将域名反向代理到 127.0.0.1:3210，并负责 HTTPS。若前置 CDN，文档 HTML 不要配置长时间缓存。

环境变量：`CONTENT_DIR` 默认本应用 content；`SITE_URL` 配置公开 origin（不含子路径）；`PORT` 默认 3210；`ADDRESS` 默认 127.0.0.1。容器默认监听 0.0.0.0，宿主端口仅绑定 loopback。

首个响应包含正文及 SEO 元信息，设置 SITE_URL 后输出 canonical 和 robots 中的 sitemap 地址；sitemap 随文件变化生成。只支持域名根路径部署。

## 运维边界

关闭在线编辑及登录。当前复用上游逐请求扫描目录，未引入缓存服务；资源开销需按实际文档规模测量。修改程序、依赖或主题才需要重新部署。支付文档来源为 near-pay 的 docs/developer；未承诺所有行业接口都已开放。

## 主题清单

黑白灰为基础色，强调色只用于链接、选中态、焦点和主要操作。桌面与手机页头均可切换，偏好存于浏览器本地，不改变服务端文档。

| 主题 | 浅色强调色 | 深色强调色 |
| --- | --- | --- |
| 极简黑（默认） | #27272A | #E4E4E7 |
| 科技蓝 | #1D4ED8 | #93C5FD |
| 鸢尾紫 | #6D28D9 | #C4B5FD |
| 翡翠绿 | #047857 | #6EE7B7 |
| 暖橙 | #C2410C | #FDBA74 |

外观：跟随系统（默认）、浅色、深色。存储受限时仍可切换当前页面。关闭 JavaScript 时使用默认浅色黑白灰，文档和导航正常阅读。

| 界面区域 | 规则与维护位置 |
| --- | --- |
| 页面、顶栏、侧栏 | surface/subtle，中性色背景 |
| 标题、正文、次级文字 | heading/text/muted，黑白灰层级 |
| 分隔线、表格、代码 | border/hover/code-*，中性色 |
| 链接、导航选中、焦点、按钮 | accent/accent-hover/accent-soft/focus/on-accent |
| 标识、浏览器栏、图标 | 中性标识；浏览器栏随明暗切换 |
| 桌面、手机主题选择器 | header.html；原生 select，支持键盘 |
| 主题偏好与恢复 | theme.js；localStorage 的 neargo-docs-theme |

配色统一在 `theme/neargo/public/portal.css` 顶部变量区维护；初始化在 theme.js，目录/复制等交互在 portal.js，无构建步骤。色彩组织参考 https://ui.shadcn.com/docs/theming ，没有引入 UI 框架或额外依赖。

## 同步 Near Pay 文档

来源是 near-pay 聊天「完善 2.0 架构与代码」整理的 `docs/developer`。同步命令只复制 README、外部 API 接入指南和三个公开示例，不复制内部接口手册或其他仓库文件：

```sh
npm run sync:nearpay
# 来源目录不在默认的同级 near-pay 仓库时：
npm run sync:nearpay -- /absolute/path/to/near-pay/docs/developer
```

near-pay 保持支付协议和示例的维护真源，门户副本只补充标题、描述和排序元信息。刷新即可看到更新，无需内容构建。`支付/samples` 三个文件以附件下载，保留相对链接，可复制到同一目录执行离线自检。同步不会更新 overview 入口文本，也不会修改 near-pay 源文件。

## 中英文内容

中文保留根路径，发布到 content；英文使用 /en/，发布到 content-en。两套目录均按请求读取 Markdown，无构建步骤。独立挂载英文目录可配置 EN_CONTENT_DIR。共享的公开支付代码与向量仍只有 content/支付/samples 一份，两种语言都从附件路由下载。

页头语言链接优先切换对应文章，未登记或未提供译文时返回另一语言首页。新增双语页面后，在 locales.mjs 的 pagePairs 登记对应网址，以生成语言切换和 hreflang；普通新增 Markdown 无需改路由，立即可访问。页面自动写入 html lang、各自 canonical；设置 SITE_URL 后，robots 同时列出中文和英文 sitemap。内容不会自动机器翻译。

已翻译：门户首页、快速开始、三个行业概览、支付概览、完整支付接入指南和示例下载页，共8对页面。API标识、JSON示例和下载代码保留协议原值。near-pay 中文源文档同步后，需复核英文译文是否应同步更新；同步命令不会覆盖英文译文。

## 生产站点

已部署：https://nearshop.umeshop.ai ，英文 /en/ 。生产目录、内容更新和回退见 [UME维护说明](deploy/ume/README.md)。TRUST_PROXY默认为不信任代理；生产按实际Nginx链显式配置，避免所有访客共用上游IP限流。
