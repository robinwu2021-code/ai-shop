# neargo 开发者门户文件渲染选型

状态：用户已授权实施 · 2026-11-12

## 一 决策
采用 Raneto 服务端动态读取 Markdown，独立应用部署，自有轻量主题。

## 二 原因
用户要求复制 Markdown 即可发布、目录自动导航、SEO、低资源占用和容易维护。VitePress 需要重新构建；Docsify 默认客户端渲染不能直接提供包含正文的首屏 HTML；Grav/Pico 需要额外 PHP 环境和主题适配。Raneto 与现有 Node 环境接近并可返回完整 HTML。

## 三 代价
需要常驻 Node 进程。上游逐请求扫描目录，大规模文档或高并发需后续实测再决定是否优化，当前不增加缓存服务。自有主题的静态资源随程序部署，Markdown 内容独立挂载。

## 四 参考
https://github.com/ryanlelek/Raneto/tree/v0.18.1
