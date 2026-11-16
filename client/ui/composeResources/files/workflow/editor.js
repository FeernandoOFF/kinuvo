(() => {
  'use strict';
  const ID_RE = /^[a-zA-Z0-9_-]+$/;
  const GAP_X = 340, GAP_Y = 300, MARGIN = 40;
  const KINDS = { agent_turn: 'Agent turn', model: 'Model', tool: 'Tool', approval: 'Approval' };
  const PREFIX = { agent_turn: 'step', model: 'model', tool: 'tool', approval: 'approval' };
  const STATUS = { Pending: 'Pending', Running: 'Running', WaitingApproval: 'Waiting for approval', Done: 'Done', Failed: 'Failed', Skipped: 'Skipped', Cancelled: 'Cancelled' };
  const THEME_VARS = { background: '--bg', panel: '--panel', subtle: '--subtle', text: '--text', muted: '--muted', border: '--border', accent: '--accent', onAccent: '--on-accent', success: '--success', warning: '--warning', danger: '--danger' };

  let wf = null, catalog = { agents: [], models: [], tools: [] }, run = null;
  let editor = null, dfOf = {}, stepOf = {}, muted = false, selected = null, timer = null;
  const expanded = new Set();
  const $ = (id) => document.getElementById(id);
  const post = (type, extra) => parent.postMessage(Object.assign({ jsonrpc: '2.0', type }, extra), '*');
  const esc = (s) => String(s == null ? '' : s).replace(/[&<>"']/g, (c) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]));
  const step = (id) => wf.steps.find((s) => s.id === id);
  const nodeEl = (id) => document.getElementById('node-' + dfOf[id]);
  const cardEl = (id) => { const n = nodeEl(id); return n && n.querySelector('.card'); };

  function emit() {
    wf.steps.forEach((s) => {
      if (!dfOf[s.id]) return;
      const n = editor.getNodeFromId(dfOf[s.id]);
      s.position = { x: Math.round(n.pos_x), y: Math.round(n.pos_y) };
    });
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
  const modelOptions = (current) => options([{ value: '', label: "Agent's model" }].concat(catalog.models.map((m) => ({ value: modelValue(m), label: m.providerName + ' / ' + m.name }))), modelValue(current));

  function fields(s) {
    switch (s.type) {
      case 'agent_turn': return `
        <label>Agent<select data-f="agentId">${agentOptions(s.agentId)}</select></label>
        <label>Prompt<textarea data-f="prompt" placeholder="What should the Agent do?">${esc(s.prompt)}</textarea></label>
        <label>Session<select data-f="session"><option value="New"${s.session !== 'Continue' ? ' selected' : ''}>New each run</option><option value="Continue"${s.session === 'Continue' ? ' selected' : ''}>Continue last</option></select></label>`;
      case 'model': return `
        <label>Prompt<textarea data-f="prompt" placeholder="What should the model answer?">${esc(s.prompt)}</textarea></label>
        <label>System prompt<textarea data-f="system" placeholder="Optional">${esc(s.system)}</textarea></label>
        <label>Model<select data-f="model">${modelOptions(s.model)}</select></label>`;
      case 'tool': return `
        <label>Tool<input data-f="tool" list="tools" autocomplete="off" autocapitalize="off" spellcheck="false" value="${esc(s.tool)}" placeholder="tool name"></label>
        <label>Arguments (JSON)<textarea class="mono" data-f="args" spellcheck="false">${esc(JSON.stringify(s.args || {}, null, 2))}</textarea></label>
        <div class="err" data-err="args"></div>`;
      default: return `
        <label>Question<textarea data-f="text" placeholder="Publish the draft?">${esc(s.text)}</textarea></label>`;
    }
  }

  const cardHtml = (s) => `<div class="card" data-step="${esc(s.id)}">
    <div class="head"><span class="kind">${KINDS[s.type] || esc(s.type)}</span><span class="badge"></span><button class="del" type="button" data-del aria-label="Delete step">&#10005;</button></div>
    <label>Step id<input class="id" data-f="id" autocomplete="off" autocapitalize="off" spellcheck="false" value="${esc(s.id)}"></label>
    <div class="err" data-err="id"></div>${fields(s)}<div class="run"></div></div>`;

  function build() {
    muted = true;
    editor.clear();
    dfOf = {}; stepOf = {};
    const pos = layout();
    wf.steps.forEach((s) => {
      const id = editor.addNode(s.type, 1, 1, pos[s.id].x, pos[s.id].y, 'k-' + s.type, { step: s.id }, cardHtml(s));
      dfOf[s.id] = id; stepOf[id] = s.id;
    });
    wf.edges.forEach((e) => { if (dfOf[e.from] && dfOf[e.to]) editor.addConnection(dfOf[e.from], dfOf[e.to], 'output_1', 'input_1'); });
    muted = false;
    document.body.classList.toggle('only', wf.steps.length < 2);
    wf.steps.forEach((s) => {
      const el = nodeEl(s.id);
      if (el && window.ResizeObserver) new ResizeObserver(() => editor.updateConnectionNodes('node-' + dfOf[s.id])).observe(el);
    });
    fillTools();
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
        parts.push(`<button class="ex ${kind}" type="button" data-ex="${esc(key)}">${open ? '&#9662;' : '&#9656;'} ${label}</button>` +
          (open ? `<pre class="out">${esc(text.length > 20000 ? text.slice(0, 20000) + '\n…' : text)}</pre>` : ''));
      };
      if (r && r.error) section('error', 'Error', r.error);
      if (r && r.output) section('output', 'Output', r.output);
      if (r && r.sessionId && s.type === 'agent_turn') parts.push(`<button class="ex" type="button" data-open="${esc(s.id)}">Open session</button>`);
      box.innerHTML = parts.join('');
    });
    wf.steps.forEach((s) => { if (dfOf[s.id]) editor.updateConnectionNodes('node-' + dfOf[s.id]); });
  }

  function applyTheme(t) {
    if (!t) return;
    Object.keys(THEME_VARS).forEach((k) => { if (t[k]) document.documentElement.style.setProperty(THEME_VARS[k], t[k]); });
    document.documentElement.style.colorScheme = t.dark ? 'dark' : 'light';
  }

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
    const s = { agent_turn: { type: 'agent_turn', id, agentId: agent, prompt: '', session: 'New' }, model: { type: 'model', id, prompt: '' },
      tool: { type: 'tool', id, tool: '', args: {} }, approval: { type: 'approval', id, text: '' } }[kind];
    s.position = { x, y };
    wf.steps.forEach((o) => { if (!o.position) o.position = { x: pos[o.id].x, y: pos[o.id].y }; });
    wf.steps.push(s);
    if (anchor) wf.edges.push({ from: anchor.id, to: id });
    build();
    selected = id;
    reveal(id);
    emit();
  }

  function rename(card, s, input) {
    const next = input.value.trim(), err = card.querySelector('[data-err=id]');
    const bad = !ID_RE.test(next) ? 'Use letters, digits, - and _' : next !== s.id && step(next) ? 'Another step has this id' : '';
    err.textContent = bad;
    input.classList.toggle('bad', !!bad);
    return bad ? null : next;
  }

  function onInput(e) {
    const f = e.target.dataset && e.target.dataset.f;
    const card = e.target.closest && e.target.closest('.card');
    if (!f || !card) return;
    const s = step(card.dataset.step);
    if (f === 'id') { rename(card, s, e.target); return; }
    const v = e.target.value;
    if (f === 'args') {
      const err = card.querySelector('[data-err=args]');
      try {
        const parsed = JSON.parse(v.trim() || '{}');
        if (parsed === null || typeof parsed !== 'object' || Array.isArray(parsed)) throw new Error('Arguments must be a JSON object');
        s.args = parsed; err.textContent = ''; e.target.classList.remove('bad');
      } catch (x) { err.textContent = 'Invalid JSON: ' + x.message; e.target.classList.add('bad'); return; }
    } else if (f === 'system') { if (v) s.system = v; else delete s.system; }
    else if (f === 'model') { const m = v ? JSON.parse(v) : null; if (m) s.model = { provider: m[0], model: m[1] }; else delete s.model; }
    else s[f] = v;
    e.target.tagName === 'SELECT' ? emit() : emitSoon();
  }

  function onChange(e) {
    const card = e.target.closest && e.target.closest('.card');
    if (!card || !e.target.dataset || e.target.dataset.f !== 'id') return;
    const s = step(card.dataset.step), next = rename(card, s, e.target);
    if (next === null) return;
    if (next === s.id) return;
    const old = s.id;
    s.id = next;
    wf.edges.forEach((x) => { if (x.from === old) x.from = next; if (x.to === old) x.to = next; });
    dfOf[next] = dfOf[old]; delete dfOf[old]; stepOf[dfOf[next]] = next;
    if (selected === old) selected = next;
    card.dataset.step = next;
    ['output', 'error'].forEach((k) => { if (expanded.delete(old + ':' + k)) expanded.add(next + ':' + k); });
    applyRun();
    emit();
  }

  function onClick(e) {
    const t = e.target;
    if (t.closest && t.closest('#add') === null) $('menu').hidden = true;
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
    editor.start();
    editor.on('nodeSelected', (id) => { selected = stepOf[id] || null; });
    editor.on('nodeUnselected', () => { selected = null; });
    editor.on('nodeMoved', () => { if (!muted) emit(); });
    editor.on('nodeRemoved', (id) => {
      if (muted) return;
      const sid = stepOf[id];
      if (!sid) return;
      if (wf.steps.length < 2) { build(); return; }
      wf.steps = wf.steps.filter((s) => s.id !== sid);
      wf.edges = wf.edges.filter((x) => x.from !== sid && x.to !== sid);
      delete stepOf[id]; delete dfOf[sid];
      document.body.classList.toggle('only', wf.steps.length < 2);
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
      wf.edges.push({ from, to });
      emit();
    });
    editor.on('connectionRemoved', (c) => {
      if (muted) return;
      const from = stepOf[c.output_id], to = stepOf[c.input_id];
      if (!from || !to) return;
      wf.edges = wf.edges.filter((x) => !(x.from === from && x.to === to));
      emit();
    });
    const root = $('df');
    root.addEventListener('input', onInput);
    root.addEventListener('change', onChange);
    document.addEventListener('click', onClick);
    $('addBtn').addEventListener('click', () => { $('menu').hidden = !$('menu').hidden; });
    $('menu').addEventListener('click', (e) => { const k = e.target.dataset && e.target.dataset.add; if (k) { $('menu').hidden = true; addStep(k); } });
    $('zoomIn').addEventListener('click', () => editor.zoom_in());
    $('zoomOut').addEventListener('click', () => editor.zoom_out());
    $('zoomReset').addEventListener('click', () => { editor.zoom_reset(); editor.canvas_x = 0; editor.canvas_y = 0; editor.zoom_refresh(); });
  }

  addEventListener('message', (e) => {
    const m = e.data;
    if (!m || typeof m !== 'object') return;
    if (m.type === 'load') {
      wf = JSON.parse(JSON.stringify(m.workflow));
      catalog = Object.assign({ agents: [], models: [], tools: [] }, m.catalog);
      run = m.run || null;
      applyTheme(m.theme);
      if (!editor) start();
      selected = null;
      build();
    } else if (!wf) { return; }
    else if (m.type === 'run') { run = m.run || null; applyRun(); }
    else if (m.type === 'catalog') { catalog = Object.assign({ agents: [], models: [], tools: [] }, m.catalog); fillSelects(); }
    else if (m.type === 'theme') applyTheme(m.theme);
  });

  post('ready');
})();
