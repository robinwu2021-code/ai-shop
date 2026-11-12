import { readFile, writeFile, mkdir } from 'node:fs/promises';
import path from 'node:path';

// Explicit publication list: never copy near-pay's internal handbook or repository.
const source = path.resolve(process.argv[2] || path.join(import.meta.dirname, '../../../near-pay/docs/developer'));
const target = path.join(import.meta.dirname, '../content/支付');
const files = ['README.md', '外部API接入指南.md', 'samples/open_api.py', 'samples/crypto.mjs', 'samples/test-vectors.json'];
const contents = await Promise.all(files.map(file => readFile(path.join(source, file))));
await mkdir(path.join(target, 'samples'), { recursive: true });
for (const [index, file] of files.entries()) {
  let body = contents[index];
  if (file === 'README.md') {
    body = '---\nTitle: 开发者文档与示例下载\nDescription: Near Pay 服务端接入指南与公开离线示例下载。\nSort: 30\n---\n\n' + body.toString('utf8').replace(/网站发布时[^\n]*\n?/, '下载示例和公开测试向量后，请保存到同一目录，再执行指南中的离线自检。正式环境接入资料由平台提供。\n');
  } else if (file === '外部API接入指南.md') {
    body = body.toString('utf8').replace(/^---\n/, '---\nSort: 20\n');
  }
  await writeFile(path.join(target, file), body);
}
await writeFile(path.join(target, 'samples/meta'), 'ShowOnMenu: false\nShowOnHome: false\n');
console.log(`已同步 ${files.length} 个公开文件至 ${target}；Markdown 无需构建或重启。`);
