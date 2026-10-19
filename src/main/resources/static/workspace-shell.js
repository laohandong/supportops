/** 工作台外壳只管理导航和视图分区，不读取或提交业务数据。 */
export function initializeWorkspaceShell({document: doc = document, window: win = window} = {}) {
    const sidebar = doc.querySelector('#workspace-navigation');
    const main = doc.querySelector('#workspace-main');
    const toggle = doc.querySelector('#navigation-toggle');
    const close = doc.querySelector('#navigation-close');
    const backdrop = doc.querySelector('#navigation-backdrop');
    const narrow = win.matchMedia('(max-width: 1080px)');
    let navigationOpen = false;

    /** 关闭时恢复触发点；切换业务页时由新页面标题承接焦点。 */
    function setNavigation(open, restoreFocus = true) {
        navigationOpen = narrow.matches && open;
        sidebar.inert = narrow.matches && !navigationOpen;
        main.inert = navigationOpen;
        backdrop.hidden = !navigationOpen;
        toggle.setAttribute('aria-expanded', String(navigationOpen));
        doc.body.classList.toggle('navigation-open', navigationOpen);
        if (navigationOpen) {
            sidebar.setAttribute('role', 'dialog');
            sidebar.setAttribute('aria-modal', 'true');
            // 等待抽屉可见样式生效，避免焦点落到仍处于隐藏过渡帧的按钮上。
            win.requestAnimationFrame(() => {
                if (navigationOpen) close.focus();
            });
        } else {
            sidebar.removeAttribute('role');
            sidebar.removeAttribute('aria-modal');
            if (restoreFocus && narrow.matches) toggle.focus();
        }
    }

    /** 排除隐藏的管理员入口，确保普通用户和管理员的焦点环均可退出。 */
    function keepNavigationFocus(event) {
        if (!navigationOpen) return;
        if (event.key === 'Escape') {
            event.preventDefault();
            setNavigation(false);
            return;
        }
        if (event.key !== 'Tab') return;
        const controls = Array.from(sidebar.querySelectorAll('a[href], button:not([disabled]), [tabindex="0"]'))
            .filter(node => node.getClientRects().length && !node.closest('[hidden]'));
        const first = controls[0];
        const last = controls.at(-1);
        const outside = !sidebar.contains(doc.activeElement);
        if (outside || (event.shiftKey && doc.activeElement === first) || (!event.shiftKey && doc.activeElement === last)) {
            event.preventDefault();
            (event.shiftKey ? last : first)?.focus();
        }
    }

    toggle.addEventListener('click', () => setNavigation(!navigationOpen));
    close.addEventListener('click', () => setNavigation(false));
    backdrop.addEventListener('click', () => setNavigation(false));
    doc.addEventListener('keydown', keepNavigationFocus);
    narrow.addEventListener('change', () => {
        const focusInSidebar = sidebar.contains(doc.activeElement);
        setNavigation(false, false);
        // 缩窄时不能把焦点留在已关闭抽屉，扩宽时不能留在隐藏的菜单按钮。
        if (narrow.matches && focusInSidebar) toggle.focus();
        if (!narrow.matches && [toggle, close].includes(doc.activeElement)) {
            sidebar.querySelector('[aria-current="page"]')?.focus();
        }
    });
    setNavigation(false, false);

    const tabs = Array.from(doc.querySelectorAll('[data-enterprise-tab]'));
    const panels = Array.from(doc.querySelectorAll('[data-enterprise-panel]'));

    /** 只切换外层视图区，保留表单实例、未保存值及内部资源类型的 hidden 状态。 */
    function selectEnterpriseTab(name, focus = false) {
        for (const tab of tabs) {
            const selected = tab.dataset.enterpriseTab === name;
            tab.setAttribute('aria-selected', String(selected));
            tab.tabIndex = selected ? 0 : -1;
            if (selected && focus) tab.focus();
        }
        for (const panel of panels) panel.hidden = panel.dataset.enterprisePanel !== name;
    }

    for (const [index, tab] of tabs.entries()) {
        tab.addEventListener('click', () => selectEnterpriseTab(tab.dataset.enterpriseTab));
        tab.addEventListener('keydown', event => {
            const offset = {ArrowRight: 1, ArrowLeft: -1}[event.key];
            let next;
            if (offset) next = (index + offset + tabs.length) % tabs.length;
            else if (event.key === 'Home') next = 0;
            else if (event.key === 'End') next = tabs.length - 1;
            else return;
            event.preventDefault();
            selectEnterpriseTab(tabs[next].dataset.enterpriseTab, true);
        });
    }
    doc.querySelector('.enterprise-tree').addEventListener('click', event => {
        if (event.target.closest('button')) selectEnterpriseTab('resources');
    });
    // 原请求恢复也可能返回一次性秘密；该结果必须出现在可见分区中。
    const secretArea = doc.querySelector('#enterprise-secret-area');
    const secretObserver = new win.MutationObserver(() => {
        if (!secretArea.hidden) selectEnterpriseTab('applications');
    });
    secretObserver.observe(secretArea, {attributes: true, attributeFilter: ['hidden']});

    /** 收起窄屏导航后把阅读起点放到当前页面，后台刷新不额外移动焦点。 */
    function pageChanged(name) {
        if (!navigationOpen) return;
        setNavigation(false, false);
        const heading = doc.querySelector(`#page-${name} h1`);
        if (heading) {
            heading.tabIndex = -1;
            heading.focus({preventScroll: true});
        }
    }

    return {pageChanged};
}
