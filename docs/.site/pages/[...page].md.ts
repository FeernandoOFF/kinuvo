import type { APIRoute } from 'astro';
import { markdown, ownerDocs } from '../owner-docs';

export async function getStaticPaths() {
  return (await ownerDocs()).map((page) => ({ params: { page: page.id }, props: { page } }));
}

export const GET: APIRoute = ({ props, site }) => {
  const { page } = props;
  const text = `# ${page.data.title}\n\n${page.data.description ?? ''}\n\n${markdown(page.body ?? '', page.id, site!)}\n`;
  return new Response(text, { headers: { 'Content-Type': 'text/markdown; charset=utf-8' } });
};
