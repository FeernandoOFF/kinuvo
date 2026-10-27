import { defineConfig } from 'astro/config';
import starlight from '@astrojs/starlight';
import { satteri } from '@astrojs/markdown-satteri';
import links from './.site/plugins/links.mjs';

export default defineConfig({
  site: 'https://feernandooff.github.io',
  base: '/kinuvo',
  devToolbar: { enabled: false },
  srcDir: './.site',
  publicDir: './.site/public',
  markdown: { processor: satteri({ mdastPlugins: [links] }) },
  integrations: [starlight({
    title: 'Kinuvo',
    favicon: '/favicon.png',
    description: 'A self-hosted personal AI assistant. Your Agents, your Server, your tools.',
    customCss: ['./.site/styles/theme.css'],
    components: { Hero: './.site/components/Hero.astro' },
    head: [{
      tag: 'script',
      // Apply the Agent picked on the landing page before first paint, on every page.
      content: `try{const a=JSON.parse(localStorage.getItem('kinuvo-agent')),c={'Tangerine Drop':['#F87828','#1F1E1B'],'Lilac Cloud':['#A888F0','#1F1E1B'],'Mint Pill':['#20C880','#1F1E1B'],'Coral Bloom':['#F86868','#1F1E1B'],'Sky Peak':['#2888F8','#FFFFFF'],'Honey Star':['#F8B020','#1F1E1B'],'Berry Orb':['#E05098','#FFFFFF'],'Cocoa Bloop':['#986040','#FFFFFF']}[a&&a.artboard];if(c){const r=document.documentElement;r.style.setProperty('--agent',c[0]);r.style.setProperty('--agent-on',c[1]);r.dataset.agent=a.artboard}}catch(e){}`,
    }],
    markdown: { processedDirs: ['./'] },
    social: [{ icon: 'github', label: 'GitHub', href: 'https://github.com/FeernandoOFF/kinuvo' }],
    sidebar: [
      { label: 'Getting started', items: ['start/install', 'start/first-server', 'start/pair-devices', 'start/first-agent'] },
      { label: 'Concepts', items: ['concepts/agents', 'concepts/model-harness-tools', 'concepts/sessions', 'concepts/approvals', 'concepts/knowledge', 'concepts/reflection', 'concepts/routines-and-tasks', 'concepts/memory-and-skills'] },
      { label: 'Guides', items: ['guides/notes', 'guides/mcp', 'guides/voice', 'guides/workspaces', 'guides/attachments', 'guides/browser-access', 'guides/computer-use', 'guides/public-hosting'] },
      { label: 'Reference', items: ['reference/config', 'reference/agent', 'reference/cli', 'reference/models', 'reference/secrets', 'reference/plugins'] },
      { label: 'Operate', items: ['operate/upgrade', 'operate/backup', 'operate/service', 'operate/troubleshooting', 'operate/tracing'] },
      { label: 'Contributing', items: ['releasing', 'local-testing'] },
    ],
  })],
  vite: { server: { allowedHosts: ['mac-mini.tail204f86.ts.net'] } },
});
