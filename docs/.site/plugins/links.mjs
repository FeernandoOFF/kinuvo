import { existsSync } from 'node:fs';
import { dirname, relative, resolve, sep } from 'node:path';
import { fileURLToPath } from 'node:url';

const docs = fileURLToPath(new URL('../../', import.meta.url));
const repo = resolve(docs, '..');

// Preserve repository-relative Markdown in source; emit site routes or source links.
export default function links({ fileURL }) {
  if (!fileURL) return;
  const source = fileURLToPath(fileURL);
  function rewrite(node) {
    if (!node.url || /^(?:[a-z]+:|\/|#)/i.test(node.url)) return;
    const [, path, suffix] = node.url.match(/^([^?#]*)(.*)$/);
    const target = resolve(dirname(source), decodeURI(path));
    if (!existsSync(target)) throw new Error(`${source}: missing link target ${node.url}`);
    const fromRepo = relative(repo, target).split(sep).join('/');
    if (fromRepo.startsWith('../')) throw new Error(`${source}: link leaves repository: ${node.url}`);
    const fromDocs = relative(docs, target).split(sep).join('/');
    let url;
    if (!fromDocs.startsWith('../') && !fromDocs.startsWith('adr/') && /\.mdx?$/.test(path)) {
      const route = fromDocs.replace(/\.mdx?$/, '').replace(/(^|\/)index$/, '$1');
      url = '/kinuvo/' + (route ? route.replace(/\/$/, '') + '/' : '') + suffix;
    } else {
      url = `https://github.com/FeernandoOFF/kinuvo/blob/main/${fromRepo}${suffix}`;
    }
    return { ...node, url };
  }
  return { name: 'kinuvo-links', link: rewrite, definition: rewrite };
}
