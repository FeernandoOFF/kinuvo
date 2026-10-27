// <kinuvo-avatar artboard="Honey Star">: the app's Rive avatars (avatars.riv, `Avatar` state machine) on the web.
// Shows the bundled still until Rive loads; with reduced motion it stays a still.
import { Layout, Fit, Alignment, Rive, RuntimeLoader, type StateMachineInput } from '@rive-app/canvas';

export const AvatarState = { Idle: 0, Listening: 1, Working: 2, NeedsYou: 3 } as const;

const base = import.meta.env.BASE_URL.replace(/\/?$/, '/');
RuntimeLoader.setWasmUrl(`${base}rive.wasm`);
const reducedMotion = matchMedia('(prefers-reduced-motion: reduce)');
const avatars = new Set<KinuvoAvatar>();
let pointer: { x: number; y: number } | null = null;

addEventListener('pointermove', (e) => {
  pointer = { x: e.clientX, y: e.clientY };
  avatars.forEach((a) => a.look());
}, { passive: true });
document.documentElement.addEventListener('pointerleave', () => {
  pointer = null;
  avatars.forEach((a) => a.look());
});

export const still = (artboard: string, state = 'idle') => `${base}avatars/avatar-${artboard.toLowerCase().replace(/ /g, '-')}-${state}.png`;

export class KinuvoAvatar extends HTMLElement {
  private rive?: Rive;
  private inputs = new Map<string, StateMachineInput>();
  private pending = AvatarState.Idle as number;
  private resize = new ResizeObserver(() => this.rive?.resizeDrawingSurfaceToCanvas());

  connectedCallback() {
    const artboard = this.getAttribute('artboard') ?? 'Tangerine Drop';
    if (!this.querySelector('img')) {
      const img = document.createElement('img');
      img.src = still(artboard);
      img.alt = '';
      this.append(img);
    }
    if (reducedMotion.matches) return;
    const canvas = document.createElement('canvas');
    this.append(canvas);
    this.rive = new Rive({
      src: `${base}avatars.riv`,
      canvas,
      artboard,
      stateMachines: 'Avatar',
      autoplay: true,
      layout: new Layout({ fit: Fit.Contain, alignment: Alignment.Center }),
      onLoad: () => {
        this.rive!.resizeDrawingSurfaceToCanvas();
        for (const input of this.rive!.stateMachineInputs('Avatar')) this.inputs.set(input.name, input);
        this.state = this.pending;
        this.look();
        this.classList.add('live');
      },
    });
    avatars.add(this);
    this.resize.observe(this);
    if (this.hasAttribute('pokeable')) {
      this.addEventListener('pointerenter', () => this.poke());
      this.addEventListener('click', () => this.poke());
    }
  }

  disconnectedCallback() {
    avatars.delete(this);
    this.resize.disconnect();
    this.rive?.cleanup();
  }

  set state(value: number) {
    this.pending = value;
    const input = this.inputs.get('state');
    if (input) input.value = value;
  }

  poke() {
    this.inputs.get('poke')?.fire();
  }

  /** Looks toward the pointer, or straight ahead without one. */
  look() {
    const x = this.inputs.get('lookX');
    const y = this.inputs.get('lookY');
    if (!x || !y) return;
    if (!pointer) {
      x.value = 50;
      y.value = 50;
      return;
    }
    const r = this.getBoundingClientRect();
    const dx = (pointer.x - (r.left + r.width / 2)) / (innerWidth / 2);
    const dy = (pointer.y - (r.top + r.height / 2)) / (innerHeight / 2);
    const clamp = (v: number) => Math.max(-1, Math.min(1, v));
    x.value = 50 + clamp(dx) * 45;
    y.value = 50 + clamp(dy) * 45;
  }
}

if (!customElements.get('kinuvo-avatar')) customElements.define('kinuvo-avatar', KinuvoAvatar);
