// The Agent the visitor picks on the landing page. Its colour themes the whole site and is remembered across pages.
// Colours are sampled from the app's own avatar artwork; `on` is the text colour that reads on top of it.

export interface AgentLook { artboard: string; color: string; on: string; name: string }

export const looks: AgentLook[] = [
  { artboard: 'Tangerine Drop', color: '#F87828', on: '#1F1E1B', name: 'Milo' },
  { artboard: 'Lilac Cloud', color: '#A888F0', on: '#1F1E1B', name: 'Luna' },
  { artboard: 'Mint Pill', color: '#20C880', on: '#1F1E1B', name: 'Pip' },
  { artboard: 'Coral Bloom', color: '#F86868', on: '#1F1E1B', name: 'Rosa' },
  { artboard: 'Sky Peak', color: '#2888F8', on: '#FFFFFF', name: 'Sky' },
  { artboard: 'Honey Star', color: '#F8B020', on: '#1F1E1B', name: 'Sunny' },
  { artboard: 'Berry Orb', color: '#E05098', on: '#FFFFFF', name: 'Bea' },
  { artboard: 'Cocoa Bloop', color: '#986040', on: '#FFFFFF', name: 'Coco' },
];

const key = 'kinuvo-agent';

export interface Agent { look: AgentLook; name: string }

export const current = (): Agent => {
  try {
    const saved = JSON.parse(localStorage.getItem(key) ?? 'null');
    const look = looks.find((l) => l.artboard === saved?.artboard);
    if (look) return { look, name: saved.name?.trim() || look.name };
  } catch {}
  return { look: looks[0], name: looks[0].name };
};

/** Applies [agent] as the site theme, saves it and tells the page (`kinuvo:agent` on document). */
export const choose = (agent: Agent) => {
  const root = document.documentElement;
  root.style.setProperty('--agent', agent.look.color);
  root.style.setProperty('--agent-on', agent.look.on);
  root.dataset.agent = agent.look.artboard;
  localStorage.setItem(key, JSON.stringify({ artboard: agent.look.artboard, name: agent.name === agent.look.name ? '' : agent.name }));
  document.dispatchEvent(new CustomEvent('kinuvo:agent', { detail: agent }));
};

export const still = (artboard: string, state = 'idle') =>
  `${import.meta.env.BASE_URL.replace(/\/?$/, '/')}avatars/avatar-${artboard.toLowerCase().replace(/ /g, '-')}-${state}.png`;
