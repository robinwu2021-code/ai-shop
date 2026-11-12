import { createPortal } from './app.mjs';

const app = createPortal({ contentDir: process.env.CONTENT_DIR, enContentDir: process.env.EN_CONTENT_DIR, siteUrl: process.env.SITE_URL, trustProxy: process.env.TRUST_PROXY?.split(',') || false });
const server = app.listen(Number(process.env.PORT || 3210), process.env.ADDRESS || '127.0.0.1', () => {
  console.log(`neargo 开发者中心：http://${process.env.ADDRESS || '127.0.0.1'}:${server.address().port}`);
});
for (const signal of ['SIGTERM', 'SIGINT']) process.on(signal, () => server.close());
