(() => {
  'use strict';
  const GAP_X = 340, GAP_Y = 300, MARGIN = 40;
  const KINDS = { agent_turn: 'Agent turn', model: 'Model', tool: 'Tool', approval: 'Approval', review: 'Review', condition: 'Condition' };
  const PREFIX = { agent_turn: 'agent', model: 'model', tool: 'tool', approval: 'approval', review: 'review', condition: 'condition' };
  const OPS = { Equals: 'equals', NotEquals: 'does not equal', Contains: 'contains', NotContains: 'does not contain', Empty: 'is empty', NotEmpty: 'is not empty', GreaterThan: 'is greater than', LessThan: 'is less than' };
  const unary = (op) => op === 'Empty' || op === 'NotEmpty';
  const STATUS = { Pending: 'Pending', Running: 'Running', WaitingApproval: 'Waiting', Done: 'Done', Failed: 'Failed', Skipped: 'Skipped', Cancelled: 'Cancelled' };
  const THEME_VARS = { background: '--bg', panel: '--panel', subtle: '--subtle', text: '--text', muted: '--muted', border: '--border', accent: '--accent', onAccent: '--on-accent', success: '--success', warning: '--warning', danger: '--danger' };

  const EMPTY = { agents: [], models: [], tools: [], inputs: [] };
  let wf = null, catalog = EMPTY, run = null;
  let editor = null, dfOf = {}, stepOf = {}, placed = {}, muted = false, selected = null, timer = null, sent = '';
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
  const modelOptions = (current) => options([{ value: '', label: "Agent's model" }].concat(catalog.models.map((m) => ({ value: modelValue(m), label: m.providerName + ' / ' + m.name }))), modelValue(current));

  const refs = (f) => `<select class="ref" data-ref="${f}" aria-label="Insert an input or the output of an earlier step"></select>`;
  const area = (f, label, value, placeholder, opts) => `<div class="fld"><div class="lbl"><span>${label}</span>${opts && opts.refs ? refs(f) : ''}</div>` +
    `<textarea${opts && opts.mono ? ' class="mono" spellcheck="false"' : ''} data-f="${f}" placeholder="${esc(placeholder)}">${esc(value)}</textarea></div>`;

  function fields(s) {
    switch (s.type) {
      case 'agent_turn': return `
        <label>Agent<select data-f="agentId">${agentOptions(s.agentId)}</select></label>
        ${area('prompt', 'Prompt', s.prompt, 'What should the Agent do?', { refs: true })}
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
      case 'review': return `
        ${area('text', 'Question', s.text, 'Is this draft ready?')}
        ${area('value', 'Value to review', s.value, 'A JSON object becomes one field per key', { refs: true, mono: true })}`;
      default: return area('text', 'Question', s.text, 'Publish the draft?');
    }
  }

  const cardHtml = (s) => `<div class="card" data-step="${esc(s.id)}">
    <div class="head"><span class="kind">${KINDS[s.type] || esc(s.type)}</span><code class="chip" title="Step id">${esc(s.id)}</code><span class="badge"></span><button class="del" type="button" data-del aria-label="Delete step">&#10005;</button></div>
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
      const any = list.length + inputs.length;
      const html = any ? '<option value="">Insert…</option>' + group('Output of', list) + group('Input', inputs) : '<option value="">Nothing to insert</option>';
      card.querySelectorAll('select.ref').forEach((sel) => {
        if (sel.dataset.h !== html) { sel.innerHTML = html; sel.dataset.h = html; }
        sel.disabled = !any;
      });
    });
  }

  function insertRef(card, field, ref) {
    const el = card.querySelector(`[data-f="${field}"]`);
    if (!el) return;
    const at = el.dataset.touched ? [el.selectionStart, el.selectionEnd] : [el.value.length, el.value.length];
    const text = '{{' + ref + '}}';
    const inString = (el.value.slice(0, at[0]).replace(/\\./g, '').match(/"/g) || []).length % 2 === 1;
    el.setRangeText(field === 'args' && !inString ? JSON.stringify(text) : text, at[0], at[1], 'end');
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
    // Typing and selecting text in a card must not drag the node, and Delete must not remove it: the delete button does.
    editor.draggable_inputs = false;
    editor.key = () => {};
    editor.start();
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
    $('addBtn').addEventListener('click', () => { $('menu').hidden = !$('menu').hidden; });
    $('menu').addEventListener('click', (e) => { const k = e.target.dataset && e.target.dataset.add; if (k) { $('menu').hidden = true; addStep(k); } });
    $('zoomIn').addEventListener('click', () => editor.zoom_in());
    $('zoomOut').addEventListener('click', () => editor.zoom_out());
    $('zoomReset').addEventListener('click', () => { editor.zoom_reset(); editor.canvas_x = 0; editor.canvas_y = 0; editor.zoom_refresh(); });
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
      selected = null;
      build();
      sent = JSON.stringify(wf);
    } else if (!wf) { return; }
    else if (m.type === 'run') { run = m.run || null; applyRun(); }
    else if (m.type === 'catalog') { catalog = Object.assign({}, EMPTY, m.catalog); fillSelects(); fillRefs(); }
    else if (m.type === 'theme') applyTheme(m.theme);
  });

  post('ready');
})();
