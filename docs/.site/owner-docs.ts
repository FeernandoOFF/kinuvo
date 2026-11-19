import { getCollection } from 'astro:content';
import { sidebar } from './sidebar.mjs';

export const sections = sidebar.filter((section) => section.label !== 'Contributing');
export const ownerIds = new Set(sections.flatMap((section) => section.items));
export const ownerDocs = () => getCollection('docs', (entry) => ownerIds.has(entry.id));

export function markdown(body: string, id: string, site: URL) {
  function rewrite(target: string) {
    if (/^(?:[a-z]+:|#)/i.test(target)) return target;
    const url = new URL(target, new URL(`${id}.md`, site));
    const route = decodeURI(url.pathname).replace(/^\//, '').replace(/\.mdx?$|\/$/g, '');
    if (ownerIds.has(route)) url.pathname = `/${route}.md`;
    else if (/\.mdx?$/.test(url.pathname)) {
      if (route.startsWith('adr/')) return `https://github.com/FeernandoOFF/kinuvo/blob/main/docs/${route}.md${url.hash}`;
      url.pathname = `/${route}/`;
    }
    return url.href;
  }
  let fence = '';
  return body.split('\n').map((line) => {
    const marker = line.match(/^\s{0,3}(`{3,}|~{3,})/);
    if (marker) {
      if (!fence) fence = marker[1];
      else if (marker[1][0] === fence[0] && marker[1].length >= fence.length) fence = '';
      return line;
    }
    if (fence) return line;
    return line.split(/(`+[^`]*`+)/g).map((part, i) => i % 2 ? part : part
      .replace(/(\]\()([^\s)]+)([^)]*\))/g, (_, before, target, after) => before + rewrite(target) + after)
      .replace(/^(\s*\[[^\]]+\]:\s*)(\S+)/, (_, before, target) => before + rewrite(target)))
      .join('');
  }).join('\n');
}
