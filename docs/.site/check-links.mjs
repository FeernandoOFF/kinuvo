import { existsSync, readFileSync, readdirSync, statSync } from 'node:fs';
import { dirname, join, relative, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const docs = fileURLToPath(new URL('../', import.meta.url));
const output = join(docs, 'dist');
const repo = resolve(docs, '..');
const origin = 'https://feernandooff.github.io';
const base = '/kinuvo/';
const errors = new Set();
const ids = new Map();
let checked = 0;
function files(dir) {
  return readdirSync(dir, { withFileTypes: true }).flatMap((entry) => {
    const path = join(dir, entry.name);
    return entry.isDirectory() ? files(path) : [path];
  });
}
function decode(value) {
  return value.replace(/&amp;/g, '&').replace(/&#39;/g, "'").replace(/&quot;/g, '"');
}
const htmlFiles = files(output).filter((path) => path.endsWith('.html'));
for (const file of htmlFiles) {
  const html = readFileSync(file, 'utf8');
  ids.set(file, new Set([...html.matchAll(/\bid="([^"]+)"/g)].map((match) => decode(match[1]))));
}
function check(raw, page) {
  const url = new URL(decode(raw), page);
  if (url.origin !== origin) {
    const prefix = 'https://github.com/FeernandoOFF/kinuvo/blob/main/';
    if (url.href.startsWith(prefix)) {
      const path = resolve(repo, decodeURIComponent(url.pathname.slice('/FeernandoOFF/kinuvo/blob/main/'.length)));
      if (!existsSync(path)) errors.add(`${page.pathname}: missing repository source ${url.pathname}`);
    }
    return;
  }
  checked++;
  if (!url.pathname.startsWith(base)) {
    errors.add(`${page.pathname}: link outside site base ${url.pathname}`);
    return;
  }
  let target = resolve(output, decodeURIComponent(url.pathname.slice(base.length)));
  if (!existsSync(target)) {
    errors.add(`${page.pathname}: missing ${url.pathname}`);
    return;
  }
  if (statSync(target).isDirectory()) target = join(target, 'index.html');
  if (!existsSync(target)) errors.add(`${page.pathname}: missing ${url.pathname}index.html`);
  else if (url.hash && ids.has(target) && !ids.get(target).has(decodeURIComponent(url.hash.slice(1)))) {
    errors.add(`${page.pathname}: missing anchor ${url.pathname}${url.hash}`);
  }
}
for (const file of htmlFiles) {
  const route = relative(output, file).replace(/index\.html$/, '');
  const page = new URL(base + route, origin);
  const html = readFileSync(file, 'utf8');
  for (const match of html.matchAll(/<(?:a|img|link|script|source|video)\b[^>]*?\b(?:href|src|poster)="([^"]+)"/g)) check(match[1], page);
}
for (const file of files(output).filter((path) => path.endsWith('.css'))) {
  const page = new URL(base + relative(output, file), origin);
  for (const match of readFileSync(file, 'utf8').matchAll(/url\(["']?([^)'"\s]+)["']?\)/g)) check(match[1], page);
}
if (errors.size) {
  console.error([...errors].sort().join('\n'));
  process.exitCode = 1;
} else {
  console.log(`Checked ${htmlFiles.length} HTML pages and ${checked} internal page, anchor and asset links; no broken links.`);
}
