import type { APIRoute } from 'astro';
import { ownerDocs, sections } from '../owner-docs';

export const GET: APIRoute = async ({ site }) => {
  const pages = new Map((await ownerDocs()).map((page) => [page.id, page]));
  const lines = ['# Kinuvo', '', '> A self-hosted personal AI assistant. Your Agents, your Server, your tools.'];
  for (const section of sections) {
    lines.push('', `## ${section.label}`, '');
    for (const id of section.items) {
      const page = pages.get(id);
      if (!page) throw new Error(`Missing owner documentation: ${id}`);
      lines.push(`- [${page.data.title}](${new URL(`${id}.md`, site)}): ${page.data.description ?? ''}`);
    }
  }
  return new Response(lines.join('\n') + '\n', { headers: { 'Content-Type': 'text/plain; charset=utf-8' } });
};
