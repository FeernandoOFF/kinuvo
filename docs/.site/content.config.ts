import { defineCollection } from 'astro:content';
import { glob } from 'astro/loaders';
import { docsSchema } from '@astrojs/starlight/schema';

export const collections = {
  docs: defineCollection({
    loader: glob({
      base: new URL('../', import.meta.url),
      pattern: ['**/[a-z0-9]*.{md,mdx}', '!node_modules/**', '!**/*.local.md', '!dist/**', '!.site/**', '!adr/**'],
    }),
    schema: docsSchema(),
  }),
};
