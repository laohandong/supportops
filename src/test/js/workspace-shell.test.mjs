import test from 'node:test';
import assert from 'node:assert/strict';
import {initializeWorkspaceShell} from '../../main/resources/static/workspace-shell.js';

/** 只模拟DOM及媒体查询，验证正式外壳控制器的焦点、隐藏和草稿保留行为。 */
function fixture(mobile = true) {
    const nodes = new Map();
    const doc = {activeElement: null, listeners: {}, addEventListener(name, callback) { this.listeners[name] = callback; }};
    function element(id) {
        const node = {
            id, hidden: false, inert: false, dataset: {}, attributes: {}, listeners: {}, value: '',
            classList: {toggle() {}},
            addEventListener(name, callback) { this.listeners[name] = callback; },
            setAttribute(name, value) { this.attributes[name] = value; },
            removeAttribute(name) { delete this.attributes[name]; },
            focus() { doc.activeElement = this; },
            getClientRects() { return this.hidden ? [] : [{}]; },
            closest() { return this.hidden ? this : null; },
            click() { this.listeners.click?.({target: this}); }
        };
        nodes.set(id, node);
        return node;
    }
    for (const id of ['workspace-navigation','workspace-main','navigation-toggle','navigation-close','navigation-backdrop','tree','heading','brand','admin-entry','active-page','enterprise-secret-area']) element(id);
    const tabs = ['resources','connections','applications'].map(name => {
        const node = element(`tab-${name}`); node.dataset.enterpriseTab = name; return node;
    });
    const panels = ['resources','connections','applications'].map(name => {
        const node = element(`panel-${name}`); node.dataset.enterprisePanel = name; return node;
    });
    const controlIds = ['brand','navigation-close','active-page','admin-entry'];
    const sidebar = nodes.get('workspace-navigation');
    sidebar.querySelectorAll = () => controlIds.map(id => nodes.get(id));
    sidebar.querySelector = () => nodes.get('active-page');
    sidebar.contains = node => controlIds.includes(node?.id);
    doc.querySelector = selector => selector === '.enterprise-tree' ? nodes.get('tree')
        : selector.includes(' h1') ? nodes.get('heading') : nodes.get(selector.slice(1));
    doc.querySelectorAll = selector => selector === '[data-enterprise-tab]' ? tabs : panels;
    doc.body = element('body');
    const media = {matches: mobile, addEventListener(name, callback) { this.changed = callback; }};
    let secretChanged;
    const shell = initializeWorkspaceShell({document: doc, window: {matchMedia: () => media, requestAnimationFrame: callback => callback(),
        MutationObserver: class { constructor(callback) { secretChanged = callback; } observe() {} }}});
    function key(name, shiftKey = false) {
        const event = {key: name, shiftKey, prevented: false, preventDefault() { this.prevented = true; }};
        doc.listeners.keydown(event);
        return event;
    }
    return {nodes, doc, media, shell, tabs, panels, key, secretChanged};
}

test('窄屏抽屉关闭时不可聚焦，打开后隔离正文，Escape恢复菜单焦点', () => {
    const f = fixture();
    assert.equal(f.nodes.get('workspace-navigation').inert, true);
    f.nodes.get('navigation-toggle').click();
    assert.equal(f.nodes.get('workspace-main').inert, true);
    assert.equal(f.nodes.get('navigation-backdrop').hidden, false);
    assert.equal(f.doc.activeElement.id, 'navigation-close');
    f.key('Escape');
    assert.equal(f.nodes.get('workspace-main').inert, false);
    assert.equal(f.nodes.get('workspace-navigation').inert, true);
    assert.equal(f.nodes.get('navigation-toggle').attributes['aria-expanded'], 'false');
    assert.equal(f.doc.activeElement.id, 'navigation-toggle');
});

test('普通用户隐藏的后台入口不进入抽屉焦点循环', () => {
    const f = fixture();
    f.nodes.get('admin-entry').hidden = true;
    f.nodes.get('navigation-toggle').click();
    f.nodes.get('active-page').focus();
    assert.equal(f.key('Tab').prevented, true);
    assert.equal(f.doc.activeElement.id, 'brand');
    f.key('Tab', true);
    assert.equal(f.doc.activeElement.id, 'active-page');
});

test('切页后关闭抽屉并聚焦新标题，后台再次刷新不抢焦点', () => {
    const f = fixture();
    f.nodes.get('navigation-toggle').click();
    f.shell.pageChanged('enterprise');
    assert.equal(f.nodes.get('workspace-main').inert, false);
    assert.equal(f.doc.activeElement.id, 'heading');
    f.nodes.get('navigation-toggle').focus();
    f.shell.pageChanged('enterprise');
    assert.equal(f.doc.activeElement.id, 'navigation-toggle');
});

test('跨桌面断点清除遮罩和正文inert，缩窄时不遗留隐藏焦点', () => {
    const f = fixture();
    f.nodes.get('navigation-toggle').click();
    f.media.matches = false; f.media.changed();
    assert.equal(f.nodes.get('workspace-navigation').inert, false);
    assert.equal(f.nodes.get('workspace-main').inert, false);
    assert.equal(f.nodes.get('navigation-backdrop').hidden, true);
    assert.equal(f.doc.activeElement.id, 'active-page');
    f.media.matches = true; f.media.changed();
    assert.equal(f.nodes.get('workspace-navigation').inert, true);
    assert.equal(f.doc.activeElement.id, 'navigation-toggle');
});

test('企业分区切换保留内容，方向键及Home/End同步选中和面板状态', () => {
    const f = fixture(false);
    f.panels[2].value = '未保存的应用草稿';
    f.tabs[2].click();
    assert.deepEqual(f.panels.map(panel => panel.hidden), [true,true,false]);
    f.tabs[2].listeners.keydown({key:'Home',preventDefault() {}});
    assert.equal(f.doc.activeElement, f.tabs[0]);
    f.tabs[0].listeners.keydown({key:'ArrowLeft',preventDefault() {}});
    assert.equal(f.doc.activeElement, f.tabs[2]);
    assert.equal(f.panels[2].value, '未保存的应用草稿');
    assert.equal(f.tabs[2].attributes['aria-selected'], 'true');
});

test('原请求恢复显示一次性秘密时自动展示应用分区', () => {
    const f = fixture(false);
    f.tabs[0].click();
    f.nodes.get('enterprise-secret-area').hidden = false;
    f.secretChanged();
    assert.equal(f.panels[2].hidden, false);
    assert.equal(f.tabs[2].attributes['aria-selected'], 'true');
});
