import path from 'node:path';
import crypto from 'node:crypto';
import { existsSync, realpathSync } from 'node:fs';
import { readdir, readFile } from 'node:fs/promises';
import express from 'express';
import raneto from 'raneto';
import { labels, pagePairs } from './locales.mjs';

const root = import.meta.dirname;

export function createPortal({ contentDir = path.join(root, 'content'), enContentDir = path.join(root, 'content-en'), siteUrl = '', trustProxy = false } = {}) {
  const app = express();
  const sampleDir = path.join(contentDir, '支付/samples');
  app.use('/en', createLocalizedPortal({ contentDir: enContentDir, siteUrl, locale: 'en', prefix: '/en', otherDir: contentDir, sampleDir, trustProxy }));
  app.use(createLocalizedPortal({ contentDir, siteUrl, locale: 'zh-CN', prefix: '', otherDir: enContentDir, sampleDir, trustProxy }));
  return app;
}

function createLocalizedPortal({ contentDir, siteUrl, locale, prefix, otherDir, sampleDir, trustProxy }) {
  const ui = labels[locale];
  const origin = siteUrl ? new URL(siteUrl).origin : '';
  const config = {
    site_title: ui.siteTitle, base_url: '', nowrap: true, path_prefix: prefix,
    content_dir: realpathSync(contentDir), theme_dir: path.join(root, 'theme'), theme_name: 'neargo',
    public_dir: path.join(root, 'theme/neargo/public'), image_url: '/images',
    secret: crypto.randomBytes(32).toString('hex'),
    authentication: false, authentication_for_edit: false, authentication_for_read: false,
    allow_editing: false, googleoauth: false, locale: locale === 'en' ? 'en' : 'zh', searchExtraLanguages: [],
    show_on_home_default: true, show_on_menu_default: true, page_sort_meta: 'sort', category_sort: true,
    excerpt_length: 180, datetime_format: 'YYYY-MM-DD', table_of_contents: false,
    home_meta: { title: ui.docs, description: ui.description },
    ...(origin ? { hostname: new URL(origin).host } : {}),
  };
  const app = express();
  app.disable('x-powered-by');
  app.get('/robots.txt', (_req, res) => res.type('text').send(
    `User-agent: *\nAllow: /\n${origin ? `Sitemap: ${origin}/sitemap.xml\nSitemap: ${origin}/en/sitemap.xml\n` : ''}`,
  ));
  // Only the three explicitly published, offline developer examples are downloadable.
  app.get(encodeURI(locale === 'en' ? '/payments/downloads/' : '/支付/samples/') + ':filename', (req, res, next) => {
    const files = ['open_api.py', 'crypto.mjs', 'test-vectors.json'];
    if (!files.includes(req.params.filename)) return next();
    res.set('Cache-Control', 'no-cache');
    res.download(path.join(sampleDir, req.params.filename));
  });
  // Local images can live next to the document. Markdown remains rendered by Raneto.
  app.use((req, res, next) => {
    if (/\.(?:svg|png|jpe?g|gif|webp|avif)$/i.test(req.path)) {
      return express.static(config.content_dir, { index: false, dotfiles: 'deny' })(req, res, next);
    }
    next();
  });
  app.use((req, res, next) => {
    res.set('Cache-Control', 'no-cache');
    const pagePath = req.path;
    if (pagePath === '/sitemap.xml') {
      const send = res.send.bind(res);
      res.send = (body) => {
        const publicOrigin = origin || `${req.protocol}://${req.get('host')}`;
        return send(typeof body === 'string' ? body
          .replace(/<loc>https:\/\/[^/]+/g, `<loc>${publicOrigin}${prefix}`)
          .replaceAll(`${publicOrigin}${prefix}/index</loc>`, `${publicOrigin}${prefix}/</loc>`) : body);
      };
    }
    const render = res.render.bind(res);
    res.render = async (view, data = {}) => {
      const navigation = (pages) => (pages || []).filter(page => page.slug !== '').map(page => ({ ...page, is_index: Boolean(page.is_index), is_directory: Boolean(page.is_directory), files: navigation(page.files) }));
      data.pages = navigation(data.pages);
      // Raneto's bundled Lunr does not tokenize Chinese. A direct substring scan
      // gives this small file-based portal predictable Chinese search without a service.
      if (view === 'search') {
        const entries = await readdir(config.content_dir, { recursive: true, withFileTypes: true });
        const query = String(req.query.search).trim().toLocaleLowerCase();
        data.searchResults = [];
        for (const entry of entries) {
          if (!entry.isFile() || !entry.name.endsWith('.md')) continue;
          const file = path.join(entry.parentPath, entry.name);
          const text = await readFile(file, 'utf8');
          const position = text.toLocaleLowerCase().indexOf(query);
          if (position < 0) continue;
          const slug = path.relative(config.content_dir, file).split(path.sep).join('/').slice(0, -3);
          data.searchResults.push({ slug: slug === 'index' ? '' : slug,
            title: text.match(/^# (.+)$/m)?.[1] || entry.name.slice(0, -3),
            excerpt: text.slice(Math.max(0, position - 35), position + 145) });
        }
      }
      if (data.content) {
        // Preserve remote URLs; local .md links resolve to the same rendered page.
        data.content = data.content.replace(/href="([^"#?]*\.md)([?#][^"]*)?"/g,
          (match, target, suffix = '') => /^(?:[a-z]+:|\/\/)/i.test(target)
            ? match : `href="${target.slice(0, -3)}${suffix}"`);
      }
      const heading = data.content?.match(/<h1[^>]*>(.*?)<\/h1>/s)?.[1].replace(/<[^>]+>/g, '');
      data.meta = { ...data.meta, title: data.meta?.custom_title || heading || data.meta?.title || ui.docs };
      data.description = data.meta.description || ui.description;
      data.canonical = origin ? origin + prefix + (pagePath === '/' ? '/' : pagePath.replace(/\/$/, '')) : '';
      data.ui = ui;
      data.locale = locale;
      data.localeBase = prefix;
      data.localeHome = prefix + '/';
      data.startUrl = locale === 'en' ? '/en/getting-started/quick-start' : '/接入指南/快速开始';
      const currentPath = prefix + (pagePath === '/' ? '/' : decodeURI(pagePath));
      const pair = pagePairs.find(paths => paths.includes(currentPath));
      const opposite = pair?.[locale === 'en' ? 0 : 1];
      const oppositeSlug = opposite?.replace(/^\/en(?=\/)/, '').replace(/^\//, '') || 'index';
      const hasTranslation = opposite && existsSync(path.join(otherDir, oppositeSlug + '.md'));
      data.switchUrl = hasTranslation ? opposite : (locale === 'en' ? '/' : '/en/');
      data.alternates = origin && hasTranslation && !['search', 'error'].includes(view)
        ? [{ language:'zh-CN', href:origin + pair[0] }, { language:'en', href:origin + pair[1] }, { language:'x-default', href:origin + pair[0] }] : [];
      data.is_search = view === 'search';
      data.is_error = view === 'error';
      data.is_home = req.path === '/';
      data.noindex = data.is_search || data.is_error;
      return render(view, data);
    };
    next();
  });
  app.use(express.static(config.public_dir, { index: false }));
  app.use((req, res, next) => {
    if (req.path === '/index') return res.redirect(308, prefix + '/');
    if (req.path.endsWith('.md')) return res.redirect(308, prefix + req.path.slice(0, -3));
    if (req.path.endsWith('/') && req.path !== '/') return res.redirect(308, prefix + req.path.slice(0, -1));
    if (req.path.endsWith('/edit')) return res.status(404).send(ui.notFound);
    // Raneto's wildcard excludes literal dots. Encode them before route matching;
    // Express decodes the captured slug before Raneto resolves the Markdown file.
    if (req.path !== '/sitemap.xml') {
      const queryStart = req.url.indexOf('?');
      const pathname = queryStart < 0 ? req.url : req.url.slice(0, queryStart);
      req.url = pathname.replaceAll('.', '%2E') + (queryStart < 0 ? '' : req.url.slice(queryStart));
    }
    next();
  });
  const docs = raneto(config);
  docs.set('trust proxy', trustProxy);
  app.use(docs);
  return app;
}
