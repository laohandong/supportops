/** 最小 DOM 只驱动实际评测模块的事件与网络状态，不代替真实浏览器验收。 */
export class Element {
  constructor(tag, text) { this.tag = tag; this.textContent = text; this.children = []; this.listeners = {}; this.nodeType = 1; this.isConnected = true; this.dataset = {}; }
  append(...nodes) { this.children.push(...nodes); }
  replaceChildren(...nodes) { this.children.forEach(node => { if (node instanceof Element) node.isConnected = false; }); this.children = [...nodes]; }
  addEventListener(name, action) { this.listeners[name] = action; }
  setAttribute() {}
  removeAttribute() {}
}
export const walk = node => node instanceof Element ? [node, ...node.children.flatMap(walk)] : [];
export function createHarness(initializeEvaluation, testContext, {deferPreflight = false, unknownStart = false, staleVersion = false} = {}) {
  const previous = Object.fromEntries(['document', 'window', 'localStorage'].map(key => [key, globalThis[key]]));
  const nodes = new Map(['#evaluation-content', '#evaluation-feedback', '#evaluation-refresh'].map(id => [id, new Element('div')]));
  const root = nodes.get('#evaluation-content'), storage = new Map(), requests = [];
  globalThis.localStorage = {getItem: key => storage.get(key), setItem: (key, value) => storage.set(key, value), removeItem: key => storage.delete(key)};
  globalThis.document = {hidden: false, addEventListener() {}, createTextNode: value => String(value)};
  globalThis.window = {addEventListener() {}};
  const a = {id: 'aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa', name: 'A', kind: 'SOURCE_CHECK', status: 'DRAFT', cleanupStatus: 'NOT_CREATED', version: 0, description: '', caseKeys: ['one'], strategies: ['LEXICAL_RRF'], repeats: 1, totalMinutes: 5};
  const b = {...a, id: 'bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb', name: 'B'};
  let finishPreflight;
  const deferred = new Promise(resolve => { finishPreflight = resolve; });
  let firstStart = true;
  const api = async (path, options) => {
    requests.push({path, method: options?.method || 'GET', body: options?.body});
    if (path === '/evaluations/capabilities') return {available: true};
    if (path === '/evaluations/datasets') return [];
    if (path.startsWith('/evaluations/experiments?')) return {items: [a, b], total: 2};
    if (path.endsWith('/preflight')) {
      const item = path.includes(a.id) ? a : b;
      const result = {preflightId: 'preflight-' + item.id, version: ++item.version, expiresAt: Date.now() + 600000, ready: true, plannedDiagnoses: 1, snapshot: {}};
      if (staleVersion) item.version++;
      if (deferPreflight && item === a) { await deferred; }
      return result;
    }
    if (path.endsWith('/clone')) return b;
    if (path.includes('/samples?')) return {items: [], groups: []};
    if (path.endsWith('/start')) {
      if (unknownStart && firstStart) { firstStart = false; throw Error('response lost'); }
      return {...a, status: 'QUEUED'};
    }
    if (path.startsWith('/evaluations/requests/')) throw Object.assign(Error('unknown request'), {code: 'EVALUATION_REQUEST_NOT_FOUND'});
    if (path === '/evaluations/experiments/' + a.id) return {...a};
    if (path === '/evaluations/experiments/' + b.id) return {...b};
    throw Error('Unexpected ' + path);
  };
  const ui = initializeEvaluation({api, el: (tag, text) => new Element(tag, text), $: id => nodes.get(id), notify() {}, isAdmin: true, userId: 'regression'});
  const button = text => walk(root).find(node => node.tag === 'button' && node.textContent === text);
  const click = async text => { const target = button(text); if (!target) throw Error('Button missing: ' + text); await target.listeners.click(); };
  const acknowledge = () => { const input = walk(root).find(node => node.tag === 'input' && node.type === 'checkbox'); input.checked = true; input.listeners.change(); };
  testContext.after(() => { ui.setActive(false); Object.entries(previous).forEach(([key, value]) => { if (value === undefined) delete globalThis[key]; else globalThis[key] = value; }); });
  return {ui, requests, storage, root, button, click, acknowledge, a, b, finishPreflight};
}
