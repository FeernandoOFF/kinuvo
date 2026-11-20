(() => {
  'use strict';
  const GAP_X = 340, GAP_Y = 300, MARGIN = 40;
  const KINDS = { text: 'Text', agent_turn: 'Agent turn', model: 'Model', tool: 'Tool', approval: 'Approval', review: 'Review', condition: 'Condition' };
  const PREFIX = { text: 'text', agent_turn: 'agent', model: 'model', tool: 'tool', approval: 'approval', review: 'review', condition: 'condition' };
  const svg = (d) => `<svg viewBox="0 0 24 24" aria-hidden="true">${d}</svg>`;
  const ICONS = {
    text: svg('<path d="M4 7V4h16v3M9 20h6M12 4v16"/>'),
    tool: svg('<path d="M14.7 6.3a1 1 0 0 0 0 1.4l1.6 1.6a1 1 0 0 0 1.4 0l3.77-3.77a6 6 0 0 1-7.94 7.94l-6.91 6.91a2.12 2.12 0 0 1-3-3l6.91-6.91a6 6 0 0 1 7.94-7.94l-3.76 3.76z"/>'),
    model: svg('<path d="M12 3l1.9 5.8L20 10.7l-6.1 1.9L12 18.5l-1.9-5.9L4 10.7l6.1-1.9z"/><path d="M19 17v4M17 19h4"/>'),
    condition: svg('<path d="M16 3h5v5M8 3H3v5M12 22v-8.3a4 4 0 0 0-1.17-2.87L3 3M15 9l6-6"/>'),
    agent_turn: svg('<path d="M12 8V4H8"/><rect width="16" height="12" x="4" y="8" rx="2"/><path d="M2 14h2M20 14h2M15 13v2M9 13v2"/>'),
    review: svg('<path d="m3 17 2 2 4-4M3 7l2 2 4-4M13 6h8M13 12h8M13 18h8"/>'),
    approval: svg('<path d="M18 11V6a2 2 0 0 0-4 0M14 10V4a2 2 0 0 0-4 0v2M10 10.5V6a2 2 0 0 0-4 0v8"/><path d="M18 8a2 2 0 1 1 4 0v6a8 8 0 0 1-8 8h-2c-2.8 0-4.5-.86-6-2.34l-3.6-3.6a2 2 0 0 1 2.83-2.82L7 15"/>'),
  };
  const MENU = [
    ['Start', [['text', 'Fixed text or JSON the next steps read']]],
    ['Automate', [['tool', 'Run a tool, an MCP tool or code'], ['model', 'Ask a model, no Session'], ['condition', 'Follow one of two branches']]],
    ['Agent', [['agent_turn', 'An Agent works on it in a Session']]],
    ['You', [['review', 'Check and edit the data'], ['approval', 'Say yes or no to continue']]],
  ];
  const NOW = [['now', 'Date and time'], ['now.date', 'Date'], ['now.time', 'Time'], ['now.weekday', 'Weekday']];
  const OPS = { Equals: 'equals', NotEquals: 'does not equal', Contains: 'contains', NotContains: 'does not contain', Empty: 'is empty', NotEmpty: 'is not empty', GreaterThan: 'is greater than', LessThan: 'is less than' };
  const unary = (op) => op === 'Empty' || op === 'NotEmpty';
  const STATUS = { Pending: 'Pending', Running: 'Running', WaitingApproval: 'Waiting', Done: 'Done', Failed: 'Failed', Skipped: 'Skipped', Cancelled: 'Cancelled' };
  const THEME_VARS = { background: '--bg', panel: '--panel', subtle: '--subtle', text: '--text', muted: '--muted', border: '--border', accent: '--accent', onAccent: '--on-accent', success: '--success', warning: '--warning', danger: '--danger' };

  const EMPTY = { agents: [], models: [], tools: [], inputs: [] };
  let wf = null, catalog = EMPTY, run = null;
  let shown = false, editor = null, dfOf = {}, stepOf = {}, placed = {}, muted = false, selected = null, timer = null, sent = '';
  const expanded = new Set();
  const $ = (id) => document.getElementById(id);
  const post = (type, extra) => parent.postMessage({ kinuvo: 'workflow', json: JSON.stringify(Object.assign({ type }, extra)) }, '*');
  const esc = (s) => String(s == null ? '' : s).replace(/[&<>"']/g, (c) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]));
  const step = (id) => wf.steps.find((s) => s.id === id);
  const nodeEl = (id) => document.getElementById('node-' + dfOf[id]);
  const cardEl = (id) => { const n = nodeEl(id); return n && n.querySelector('.card'); };

  /** Only user edits get here; an edit that leaves the graph as it was sent is not a change. */
  function emit() {
    wf.steps.forEach((s) => {
      if (!dfOf[s.id]) return;
      const n = editor.getNodeFromId(dfOf[s.id]);
      s.position = { x: Math.round(n.pos_x), y: Math.round(n.pos_y) };
    });
    const json = JSON.stringify(wf);
    if (json === sent) return;
    sent = json;
    post('changed', { workflow: wf });
  }
  const emitSoon = () => { clearTimeout(timer); timer = setTimeout(emit, 150); };

  function layout() {
    const depth = {};
    const incoming = (id) => wf.edges.filter((e) => e.to === id).map((e) => e.from);
    const depthOf = (id, seen) => {
      if (depth[id] != null) return depth[id];
      const d = incoming(id).filter((p) => !seen.includes(p)).reduce((m, p) => Math.max(m, depthOf(p, seen.concat(id)) + 1), 0);
      return (depth[id] = d);
    };
    const rows = {}, out = {};
    wf.steps.forEach((s) => {
      if (s.position) { out[s.id] = s.position; return; }
      const c = depthOf(s.id, []);
      const r = rows[c] || 0;
      rows[c] = r + 1;
      out[s.id] = { x: MARGIN + c * GAP_X, y: MARGIN + 40 + r * GAP_Y };
    });
    return out;
  }

  const options = (items, current) => {
    const list = items.slice();
    if (current != null && !list.some((i) => i.value === current)) list.push({ value: current, label: current });
    return list.map((i) => `<option value="${esc(i.value)}"${i.value === current ? ' selected' : ''}>${esc(i.label)}</option>`).join('');
  };
  const agentOptions = (current) => options(catalog.agents.map((a) => ({ value: a.id, label: a.name })), current);
  const modelValue = (m) => (m ? JSON.stringify([m.provider, m.model]) : '');
  const modelLabel = (m) => { const c = catalog.models.find((x) => x.provider === m.provider && x.model === m.model); return c ? c.providerName + ' / ' + c.name : m.provider + ' / ' + m.model; };
  const modelOptions = (current) => options([{ value: '', label: "Agent's model" }].concat(catalog.models.map((m) => ({ value: modelValue(m), label: m.providerName + ' / ' + m.name }))), modelValue(current));

  const refs = (f) => `<select class="ref" data-ref="${f}" aria-label="Insert an input or the output of an earlier step"></select>`;
  const area = (f, label, value, placeholder, opts) => `<div class="fld"><div class="lbl"><span>${label}</span>${opts && opts.refs ? refs(f) : ''}</div>` +
    `<textarea${opts && opts.mono ? ' class="mono" spellcheck="false"' : ''} data-f="${f}" placeholder="${esc(placeholder)}">${esc(value)}</textarea></div>`;

  function fields(s) {
    switch (s.type) {
      case 'agent_turn': return `
        <label>Agent<select data-f="agentId">${agentOptions(s.agentId)}</select></label>
        ${area('prompt', 'Prompt', s.prompt, 'What should the Agent do?', { refs: true })}
        <label>Model<select data-f="model">${modelOptions(s.model)}</select></label>
        <label>Session<select data-f="session"><option value="New"${s.session !== 'Continue' ? ' selected' : ''}>New each run</option><option value="Continue"${s.session === 'Continue' ? ' selected' : ''}>Continue last</option></select></label>`;
      case 'model': return `
        ${area('prompt', 'Prompt', s.prompt, 'What should the model answer?', { refs: true })}
        ${area('system', 'System prompt', s.system, 'Optional', { refs: true })}
        <label>Model<select data-f="model">${modelOptions(s.model)}</select></label>`;
      case 'tool': return `
        <label>Tool<input data-f="tool" list="tools" autocomplete="off" autocapitalize="off" spellcheck="false" value="${esc(s.tool)}" placeholder="tool name"></label>
        ${area('args', 'Arguments (JSON)', JSON.stringify(s.args || {}, null, 2), '', { refs: true, mono: true })}
        <div class="err" data-err="args"></div>`;
      case 'condition': return `
        ${area('left', 'If', s.left, 'A value, usually from an earlier step', { refs: true })}
        <label>Is<select data-f="op">${options(Object.keys(OPS).map((k) => ({ value: k, label: OPS[k] })), s.op || 'Equals')}</select></label>
        <div data-right${unary(s.op) ? ' hidden' : ''}>${area('right', 'Value', s.right, 'true', { refs: true })}</div>`;
      case 'text': return area('text', 'Text', s.text, 'Text or JSON for the next steps', { refs: true });
      case 'review': return `
        ${area('text', 'Question', s.text, 'Is this draft ready?', { refs: true })}
        ${area('value', 'Value to review', s.value, 'A JSON object becomes one field per key, an array of objects one group per item', { refs: true, mono: true })}
        ${area('surface', 'Custom form (A2UI surface, optional)', s.surface ? JSON.stringify(s.surface, null, 2) : '', 'createSurface JSON; its data model is the output', { refs: true, mono: true })}
        <div class="err" data-err="surface"></div>`;
      default: return area('text', 'Question', s.text, 'Publish the draft?', { refs: true });
    }
  }

  const cardHtml = (s) => `<div class="card" data-step="${esc(s.id)}">
    <div class="head"><span class="ico">${ICONS[s.type] || ''}</span><span class="kind">${KINDS[s.type] || esc(s.type)}</span><code class="chip" title="Step id">${esc(s.id)}</code><span class="badge"></span><button class="del" type="button" data-del aria-label="Delete step">&#10005;</button></div>
    ${fields(s)}<div class="run"></div>${s.type === 'condition' ? '<div class="ports"><span>true &rarr;</span><span>false &rarr;</span></div>' : ''}</div>`;

  function addCard(s, at) {
    muted = true;
    const id = editor.addNode(s.type, 1, s.type === 'condition' ? 2 : 1, at.x, at.y, 'k-' + s.type, { step: s.id }, cardHtml(s));
    muted = false;
    dfOf[s.id] = id; stepOf[id] = s.id; placed[s.id] = { x: at.x, y: at.y };
    const el = nodeEl(s.id);
    if (el && window.ResizeObserver) new ResizeObserver(() => editor.updateConnectionNodes('node-' + id)).observe(el);
  }

  const ancestors = (id) => {
    const seen = new Set();
    const walk = (to) => wf.edges.forEach((e) => { if (e.to === to && !seen.has(e.from)) { seen.add(e.from); walk(e.from); } });
    walk(id);
    return wf.steps.map((s) => s.id).filter((x) => seen.has(x));
  };

  function fillRefs() {
    wf.steps.forEach((s) => {
      const card = cardEl(s.id);
      if (!card) return;
      const list = ancestors(s.id).map((x) => ({ value: 'steps.' + x + '.output', label: x }));
      const inputs = (catalog.inputs || []).map((x) => ({ value: 'input.' + x, label: x }));
      const group = (label, items) => (items.length ? `<optgroup label="${label}">` + items.map((x) => `<option value="${esc(x.value)}">${esc(x.label)}</option>`).join('') + '</optgroup>' : '');
      const now = NOW.map(([value, label]) => ({ value, label }));
      const html = '<option value="">Insert…</option>' + group('Output of', list) + group('Input', inputs) + group('Now', now);
      card.querySelectorAll('select.ref').forEach((sel) => {
        if (sel.dataset.h !== html) { sel.innerHTML = html; sel.dataset.h = html; }
      });
    });
  }

  function insertRef(card, field, ref) {
    const el = card.querySelector(`[data-f="${field}"]`);
    if (!el) return;
    const at = el.dataset.touched ? [el.selectionStart, el.selectionEnd] : [el.value.length, el.value.length];
    const text = '{{' + ref + '}}';
    const inString = (el.value.slice(0, at[0]).replace(/\\./g, '').match(/"/g) || []).length % 2 === 1;
    el.setRangeText((field === 'args' || field === 'surface') && !inString ? JSON.stringify(text) : text, at[0], at[1], 'end');
    el.dataset.touched = '1';
    el.focus();
    el.dispatchEvent(new Event('input', { bubbles: true }));
  }

  function build() {
    muted = true;
    editor.clear();
    muted = false;
    dfOf = {}; stepOf = {}; placed = {};
    const pos = layout();
    wf.steps.forEach((s) => addCard(s, pos[s.id]));
    muted = true;
    wf.edges.forEach((e) => { if (dfOf[e.from] && dfOf[e.to]) editor.addConnection(dfOf[e.from], dfOf[e.to], port(e), 'input_1'); });
    muted = false;
    document.body.classList.toggle('only', wf.steps.length < 2);
    fillTools();
    fillRefs();
    applyRun();
  }

  function fillTools() {
    $('tools').innerHTML = (catalog.tools || []).map((t) => `<option value="${esc(t)}">`).join('');
  }

  function fillSelects() {
    wf.steps.forEach((s) => {
      const card = cardEl(s.id);
      if (!card) return;
      const agent = card.querySelector('[data-f=agentId]');
      if (agent) agent.innerHTML = agentOptions(s.agentId);
      const model = card.querySelector('[data-f=model]');
      if (model) model.innerHTML = modelOptions(s.model);
    });
    fillTools();
  }

  function applyRun() {
    const steps = (run && run.steps) || {};
    wf.steps.forEach((s) => {
      const card = cardEl(s.id);
      if (!card) return;
      const r = steps[s.id];
      Object.keys(STATUS).forEach((k) => card.classList.remove('st-' + k));
      const badge = card.querySelector('.badge');
      badge.textContent = r ? STATUS[r.status] || r.status : '';
      badge.dataset.st = r ? r.status : '';
      if (r) card.classList.add('st-' + r.status);
      const box = card.querySelector('.run');
      const parts = [];
      const section = (kind, label, text) => {
        const key = s.id + ':' + kind, open = expanded.has(key);
        parts.push(`<button class="ex" type="button" data-kind="${kind}" data-ex="${esc(key)}">${open ? '&#9662;' : '&#9656;'} ${label}</button>` +
          (open ? `<pre class="out">${esc(text.length > 20000 ? text.slice(0, 20000) + '\n…' : text)}</pre>` : ''));
      };
      if (r && r.error) section('error', 'Error', r.error);
      if (r && r.output) section('output', 'Output', r.output);
      if (r && r.model) parts.push(`<span class="used" title="Model used">${esc(modelLabel(r.model))}</span>`);
      if (r && r.sessionId) parts.push(`<button class="ex" type="button" data-open="${esc(s.id)}">Open session</button>`);
      const html = parts.join('');
      if (box.dataset.h !== html) { box.innerHTML = html; box.dataset.h = html; }
    });
  }

  function applyTheme(t) {
    if (!t) return;
    Object.keys(THEME_VARS).forEach((k) => { if (t[k]) document.documentElement.style.setProperty(THEME_VARS[k], t[k]); });
    document.documentElement.style.colorScheme = t.dark ? 'dark' : 'light';
  }

  /** Shows the whole graph, shrunk if it has to be, under the toolbar. */
  function fit() {
    const host = $('df');
    let x0 = Infinity, y0 = Infinity, x1 = -Infinity, y1 = -Infinity;
    wf.steps.forEach((s) => {
      const el = nodeEl(s.id);
      if (!el) return;
      const n = editor.getNodeFromId(dfOf[s.id]);
      x0 = Math.min(x0, n.pos_x); y0 = Math.min(y0, n.pos_y);
      x1 = Math.max(x1, n.pos_x + el.offsetWidth); y1 = Math.max(y1, n.pos_y + el.offsetHeight);
    });
    const w = host.clientWidth, h = host.clientHeight, top = 52, pad = 24;
    if (x1 < x0 || !w || !h) return;
    // Below this cards are unreadable; a graph that still does not fit starts at its first step.
    const z = Math.max(0.35, Math.min(1, (w - 2 * pad) / (x1 - x0), (h - top - pad) / (y1 - y0)));
    editor.zoom_min = Math.min(editor.zoom_min, z);
    editor.zoom = z; editor.zoom_last_value = z;
    // The canvas scales about its centre, so a point p lands at centre + (p - centre) * z + offset.
    const place = (from, to, size, view, start) => ((to - from) * z > view ? start - size / 2 - (from - size / 2) * z : start + view / 2 - size / 2 - ((from + to) / 2 - size / 2) * z);
    editor.canvas_x = place(x0, x1, w, w - 2 * pad, pad);
    editor.canvas_y = place(y0, y1, h, h - top - pad, top);
    editor.precanvas.style.transform = `translate(${editor.canvas_x}px, ${editor.canvas_y}px) scale(${z})`;
    wf.steps.forEach((s) => editor.updateConnectionNodes('node-' + dfOf[s.id]));
    zoomLabel();
  }
  const zoomLabel = () => { $('zoomFit').textContent = Math.round(editor.zoom * 100) + '%'; };

  /** Drawflow's edge is a 3px line; a wide invisible copy on top of it is what a finger or pointer hits. */
  function widenEdges() {
    const sync = () => editor.precanvas.querySelectorAll('svg.connection').forEach((c) => {
      const line = c.children[0];
      if (!line) return;
      let hit = c.querySelector('path.hit');
      if (!hit) { hit = document.createElementNS('http://www.w3.org/2000/svg', 'path'); hit.setAttribute('class', 'main-path hit'); c.appendChild(hit); }
      const d = line.getAttribute('d');
      if (hit.getAttribute('d') !== d) hit.setAttribute('d', d);
    });
    new MutationObserver((list) => { if (list.some((m) => !(m.target.classList && m.target.classList.contains('hit')))) sync(); })
      .observe(editor.precanvas, { subtree: true, childList: true, attributes: true, attributeFilter: ['d'] });
  }

  function menuHtml() {
    return MENU.map(([group, items]) => `<div class="grp">${group}</div>` + items.map(([k, hint]) =>
      `<button type="button" role="menuitem" data-add="${k}" class="k-${k}"><span class="ico">${ICONS[k]}</span><span class="txt"><b>${KINDS[k]}</b><small>${hint}</small></span></button>`).join('')).join('');
  }
  const toggleMenu = (open) => { $('menu').hidden = !open; $('addBtn').setAttribute('aria-expanded', String(open)); };

  function freeId(kind) {
    const taken = new Set(wf.steps.map((s) => s.id));
    for (let n = 1; ; n++) if (!taken.has(PREFIX[kind] + '-' + n)) return PREFIX[kind] + '-' + n;
  }

  function reveal(id) {
    const el = nodeEl(id), host = $('df');
    if (!el) return;
    const r = el.getBoundingClientRect(), h = host.getBoundingClientRect();
    let dx = 0, dy = 0;
    if (r.right > h.right - 16) dx = h.right - 16 - r.right;
    if (r.left + dx < h.left + 16) dx = h.left + 16 - r.left;
    if (r.bottom > h.bottom - 16) dy = h.bottom - 16 - r.bottom;
    if (r.top + dy < h.top + 56) dy = h.top + 56 - r.top;
    if (!dx && !dy) return;
    editor.canvas_x += dx; editor.canvas_y += dy;
    editor.precanvas.style.transform = `translate(${editor.canvas_x}px, ${editor.canvas_y}px) scale(${editor.zoom})`;
  }

  function addStep(kind) {
    const anchor = selected && step(selected);
    const pos = layout();
    const at = anchor && pos[anchor.id];
    const x = at ? at.x + GAP_X : MARGIN + (wf.steps.length ? Math.max(...wf.steps.map((s) => pos[s.id].x)) - MARGIN + GAP_X : 0);
    const same = wf.steps.map((s) => pos[s.id]).filter((p) => Math.abs(p.x - x) < 1);
    const y = at && !same.length ? at.y : (same.length ? Math.max(...same.map((p) => p.y)) + GAP_Y : MARGIN + 40);
    const id = freeId(kind);
    const agent = (anchor && anchor.agentId) || wf.agentId || (catalog.agents[0] && catalog.agents[0].id) || '';
    const s = { text: { type: 'text', id, text: '' }, agent_turn: { type: 'agent_turn', id, agentId: agent, prompt: '', session: 'New' }, model: { type: 'model', id, prompt: '' },
      tool: { type: 'tool', id, tool: '', args: {} }, approval: { type: 'approval', id, text: '' }, review: { type: 'review', id, text: '', value: '' },
      condition: { type: 'condition', id, left: anchor ? '{{steps.' + anchor.id + '.output}}' : '', op: 'Equals', right: 'true' } }[kind];
    s.position = { x, y };
    wf.steps.forEach((o) => { if (!o.position) o.position = { x: pos[o.id].x, y: pos[o.id].y }; });
    wf.steps.push(s);
    addCard(s, s.position);
    if (anchor) {
      const e = { from: anchor.id, to: id };
      if (anchor.type === 'condition') e.branch = !wf.edges.some((x) => x.from === anchor.id && x.branch === true);
      wf.edges.push(e); connect(e);
    }
    document.body.classList.toggle('only', wf.steps.length < 2);
    fillTools(); fillRefs(); applyRun();
    selected = id;
    reveal(id);
    emit();
  }

  /** A Condition's first output is its true branch and its second the false one. */
  const port = (e) => (e.branch === false ? 'output_2' : 'output_1');
  const isCondition = (id) => (step(id) || {}).type === 'condition';

  function connect(e) {
    muted = true;
    editor.addConnection(dfOf[e.from], dfOf[e.to], port(e), 'input_1');
    muted = false;
  }

  function onInput(e) {
    const f = e.target.dataset && e.target.dataset.f;
    const card = e.target.closest && e.target.closest('.card');
    if (!f || !card) return;
    const s = step(card.dataset.step);
    const v = e.target.value;
    if (f === 'args') {
      const err = card.querySelector('[data-err=args]');
      try {
        const parsed = JSON.parse(v.trim() || '{}');
        if (parsed === null || typeof parsed !== 'object' || Array.isArray(parsed)) throw new Error('Arguments must be a JSON object');
        s.args = parsed; err.textContent = ''; e.target.classList.remove('bad');
      } catch (x) { err.textContent = 'Invalid JSON: ' + x.message; e.target.classList.add('bad'); return; }
    } else if (f === 'surface') {
      const err = card.querySelector('[data-err=surface]');
      try {
        const parsed = v.trim() ? JSON.parse(v) : null;
        if (parsed !== null && (typeof parsed !== 'object' || Array.isArray(parsed))) throw new Error('The surface must be a JSON object');
        if (parsed) s.surface = parsed; else delete s.surface;
        err.textContent = ''; e.target.classList.remove('bad');
      } catch (x) { err.textContent = 'Invalid JSON: ' + x.message; e.target.classList.add('bad'); return; }
    } else if (f === 'system') { if (v) s.system = v; else delete s.system; }
    else if (f === 'model') { const m = v ? JSON.parse(v) : null; if (m) s.model = { provider: m[0], model: m[1] }; else delete s.model; }
    else s[f] = v;
    if (f === 'op') card.querySelector('[data-right]').hidden = unary(v);
    e.target.tagName === 'SELECT' ? emit() : emitSoon();
  }

  function onChange(e) {
    const field = e.target.dataset && e.target.dataset.ref;
    const card = e.target.closest && e.target.closest('.card');
    if (!field || !card) return;
    if (e.target.value) insertRef(card, field, e.target.value);
    e.target.value = '';
  }

  function onClick(e) {
    const t = e.target;
    if (t.closest && t.closest('#add') === null) toggleMenu(false);
    const ex = t.closest && t.closest('[data-ex]');
    if (ex) { const k = ex.dataset.ex; expanded.has(k) ? expanded.delete(k) : expanded.add(k); applyRun(); return; }
    const sess = t.closest && t.closest('[data-open]');
    if (sess) { post('open_session', { step: sess.dataset.open }); return; }
    const del = t.closest && t.closest('[data-del]');
    if (del) editor.removeNodeId('node-' + dfOf[del.closest('.card').dataset.step]);
  }

  function start() {
    editor = new Drawflow($('df'));
    editor.reroute = false;
    // Typing and selecting text in a card must not drag the node, and Delete must not remove it: the delete button does.
    editor.draggable_inputs = false;
    editor.key = () => {};
    // A connection dropped anywhere on a card goes to its input.
    editor.force_first_input = true;
    editor.start();
    widenEdges();
    let pointer = { x: 0, y: 0 };
    $('df').addEventListener('pointerdown', (e) => { pointer = { x: e.clientX, y: e.clientY }; }, true);
    const edgeDel = $('edgeDel');
    editor.on('connectionSelected', () => {
      edgeDel.hidden = false;
      const w = edgeDel.offsetWidth, h = edgeDel.offsetHeight;
      edgeDel.style.left = Math.max(8, Math.min(innerWidth - w - 8, pointer.x - w / 2)) + 'px';
      edgeDel.style.top = Math.max(52, Math.min(innerHeight - h - 8, pointer.y - h - 14)) + 'px';
    });
    editor.on('connectionUnselected', () => { edgeDel.hidden = true; });
    // A short edge hides under its ports, so a tap on an input picks its connections in turn.
    $('df').addEventListener('pointerup', (e) => {
      const input = e.target.closest && e.target.closest('.input');
      if (!input || Math.hypot(e.clientX - pointer.x, e.clientY - pointer.y) > 10) return;
      const node = input.closest('.drawflow-node').id;
      const hits = [...editor.precanvas.querySelectorAll(`svg.connection.node_in_${node} path.hit`)];
      if (!hits.length) return;
      const next = hits[(hits.indexOf(editor.connection_selected) + 1) % hits.length];
      if (editor.node_selected) { editor.node_selected.classList.remove('selected'); editor.node_selected = null; editor.dispatch('nodeUnselected', true); }
      if (editor.connection_selected) editor.connection_selected.classList.remove('selected');
      editor.connection_selected = next; next.classList.add('selected');
      editor.dispatch('connectionSelected', {});
    });
    edgeDel.addEventListener('click', () => { editor.removeConnection(); edgeDel.hidden = true; });
    document.addEventListener('keydown', (e) => {
      if ((e.key === 'Delete' || e.key === 'Backspace') && editor.connection_selected && !/INPUT|TEXTAREA|SELECT/.test(e.target.tagName)) {
        editor.removeConnection(); edgeDel.hidden = true; e.preventDefault();
      }
    });
    editor.on('zoom', zoomLabel);
    $('menu').innerHTML = menuHtml();
    editor.on('nodeSelected', (id) => { selected = stepOf[id] || null; });
    editor.on('nodeUnselected', () => { selected = null; });
    editor.on('nodeMoved', (id) => {
      const sid = stepOf[id], at = placed[sid], n = sid && editor.getNodeFromId(id);
      if (muted || !n || (Math.abs(n.pos_x - at.x) < 1 && Math.abs(n.pos_y - at.y) < 1)) return;
      placed[sid] = { x: n.pos_x, y: n.pos_y };
      emit();
    });
    editor.on('nodeRemoved', (id) => {
      if (muted) return;
      const sid = stepOf[id];
      if (!sid) return;
      if (wf.steps.length < 2) { build(); return; }
      wf.steps = wf.steps.filter((s) => s.id !== sid);
      wf.edges = wf.edges.filter((x) => x.from !== sid && x.to !== sid);
      delete stepOf[id]; delete dfOf[sid]; delete placed[sid];
      document.body.classList.toggle('only', wf.steps.length < 2);
      fillRefs();
      emit();
    });
    editor.on('connectionCreated', (c) => {
      if (muted) return;
      const from = stepOf[c.output_id], to = stepOf[c.input_id];
      if (!from || !to || from === to || wf.edges.some((x) => x.from === from && x.to === to)) {
        muted = true;
        editor.removeSingleConnection(c.output_id, c.input_id, c.output_class, c.input_class);
        muted = false;
        return;
      }
      const edge = { from, to };
      if (isCondition(from)) edge.branch = c.output_class !== 'output_2';
      wf.edges.push(edge);
      fillRefs();
      emit();
    });
    editor.on('connectionRemoved', (c) => {
      if (muted) return;
      const from = stepOf[c.output_id], to = stepOf[c.input_id];
      if (!from || !to) return;
      wf.edges = wf.edges.filter((x) => !(x.from === from && x.to === to));
      fillRefs();
      emit();
    });
    const root = $('df');
    root.addEventListener('input', onInput);
    root.addEventListener('change', onChange);
    root.addEventListener('focusin', (e) => { if (e.target.dataset) e.target.dataset.touched = '1'; });
    document.addEventListener('click', onClick);
    $('addBtn').addEventListener('click', () => toggleMenu($('menu').hidden));
    $('menu').addEventListener('click', (e) => { const b = e.target.closest('[data-add]'); if (b) { toggleMenu(false); addStep(b.dataset.add); } });
    $('zoomIn').addEventListener('click', () => editor.zoom_in());
    $('zoomOut').addEventListener('click', () => editor.zoom_out());
    $('zoomFit').addEventListener('click', fit);
  }

  addEventListener('message', (e) => {
    const m = e.data;
    if (e.source !== parent || !m || typeof m !== 'object') return;
    if (m.type === 'load') {
      wf = JSON.parse(JSON.stringify(m.workflow));
      catalog = Object.assign({}, EMPTY, m.catalog);
      run = m.run || null;
      applyTheme(m.theme);
      if (!editor) start();
      const first = !shown;
      shown = true;
      selected = null;
      build();
      sent = JSON.stringify(wf);
      zoomLabel();
      // A phone shows one card at full size, so it opens on the whole graph.
      if (first && wf.steps.length > 1 && $('df').clientWidth < 600) requestAnimationFrame(fit);
    } else if (!wf) { return; }
    else if (m.type === 'run') { run = m.run || null; applyRun(); }
    else if (m.type === 'catalog') { catalog = Object.assign({}, EMPTY, m.catalog); fillSelects(); fillRefs(); }
    else if (m.type === 'theme') applyTheme(m.theme);
  });

  post('ready');
})();
