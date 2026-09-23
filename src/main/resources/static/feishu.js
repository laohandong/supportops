/** 飞书管理沿用工作台认证；身份、消息和状态一律通过 textContent 展示。 */
export function initializeFeishu({api, el, action, $, isAdmin}) {
    let epoch = 0;
    let issuedId = null;
    const names = new Map();
    const connectionNames = {
        DISABLED: '未启用', CONNECTING: '正在连接飞书', CONNECTED: '已连接飞书',
        RECONNECTING: '正在重新连接', ERROR: '连接异常，请检查应用配置', STOPPED: '已停止'
    };
    const receiveNames = {RECEIVED: '待受理', WAITING: '等待诊断结果', FINISHED: '已生成回复', BLOCKED: '绑定已失效'};
    const deliveryNames = {PENDING: '待投递', SENT: '已送达', FAILED: '重试耗尽，送达待核查', UNKNOWN: '超过恢复窗口，送达待核查', BLOCKED: '绑定已失效，停止投递'};
    const kindNames = {NOTICE: '绑定提示', ACCEPTED: '受理提示', RESULT: '诊断结果'};

    /** 同一批读取完成后整体更新，失败保留现有绑定命令和可见状态。 */
    async function refresh() {
        if (!isAdmin) return;
        const current = ++epoch;
        const [status, users, bindings, messages] = await Promise.all([
            api('/feishu/status'), api('/users'), api('/feishu/bindings'), api('/feishu/messages')
        ]);
        if (current !== epoch) return;
        $('#feishu-status').textContent = connectionNames[status.connection] || '状态未知';
        $('#feishu-config-help').textContent = status.configured
            ? `应用：${status.appId} · 企业：${status.tenantKey}`
            : '请在本机配置文件中填写飞书应用编号、密钥和企业编号，并启用后重启服务。';
        const selected = $('#feishu-user').value;
        $('#feishu-user').replaceChildren();
        names.clear();
        for (const user of users) {
            names.set(user.id, user.username);
            const option = el('option', user.username);
            option.value = user.id;
            $('#feishu-user').append(option);
        }
        if (users.some(user => user.id === selected)) $('#feishu-user').value = selected;
        $('#feishu-issue').disabled = !status.configured || !users.length;
        if (issuedId) {
            const issued = bindings.find(binding => binding.id === issuedId);
            if (issued && (issued.revoked || issued.openId || issued.expiresAt <= Date.now())) {
                clearIssuedCommand();
            }
        }
        renderBindings(bindings);
        renderMessages(messages);
    }

    /** 已消费、到期或撤销的命令不再展示，避免继续转发失效凭据。 */
    function clearIssuedCommand() {
        issuedId = null;
        $('#feishu-command').value = '';
        $('#feishu-expiry').textContent = '';
        $('#feishu-issued').hidden = true;
    }

    /** 撤销按钮只改变指定绑定，已有诊断仍保留。 */
    function renderBindings(bindings) {
        const target = $('#feishu-bindings');
        target.replaceChildren();
        if (!bindings.length) target.append(el('p', '尚无绑定记录。', 'muted'));
        for (const binding of bindings) {
            const row = el('div', undefined, 'user-row');
            const info = el('div');
            let label = '等待私聊绑定';
            if (binding.revoked) label = '已撤销';
            else if (binding.openId) label = '已绑定';
            else if (binding.expiresAt <= Date.now()) label = '绑定码已过期';
            info.append(el('strong', names.get(binding.userId) || binding.userId), el('p', label));
            if (binding.openId) info.append(el('p', binding.openId));
            row.append(info);
            if (!binding.revoked) {
                const revoke = el('button', '撤销绑定', 'secondary');
                revoke.type = 'button';
                revoke.addEventListener('click', () => action(revoke, async () => {
                    await api(`/feishu/bindings/${encodeURIComponent(binding.id)}/delete`, {method: 'POST'});
                    if (issuedId === binding.id) clearIssuedCommand();
                    await refresh();
                }));
                row.append(revoke);
            }
            target.append(row);
        }
    }

    /** 区分处理和投递结果，不以“已生成回复”冒充消息已送达。 */
    function renderMessages(messages) {
        const target = $('#feishu-messages');
        target.replaceChildren();
        if (!messages.length) target.append(el('p', '尚未收到消息。启用机器人后，可先发送一次绑定命令。', 'muted'));
        for (const message of messages) {
            const row = el('div', undefined, 'user-row');
            const info = el('div');
            info.append(el('strong', receiveNames[message.state] || message.state),
                el('p', `${message.userId ? names.get(message.userId) || message.userId : '尚未绑定'} · ${new Date(message.createdAt).toLocaleString('zh-CN')}`),
                el('p', `请求编号：${message.id}`));
            for (const delivery of message.deliveries) {
                info.append(el('p', `${kindNames[delivery.kind] || delivery.kind}：${deliveryNames[delivery.state] || delivery.state} · 已尝试 ${delivery.attempts} 次`));
            }
            if (message.errorCode) info.append(el('p', `受理说明：${message.errorCode}`));
            row.append(info);
            target.append(row);
        }
    }

    $('#feishu-refresh').addEventListener('click', () => action($('#feishu-refresh'), refresh));
    $('#feishu-binding-form').addEventListener('submit', event => {
        event.preventDefault();
        if (!isAdmin) return;
        action($('#feishu-issue'), async () => {
            const code = await api('/feishu/bindings', {method: 'POST', body: {userId: $('#feishu-user').value}});
            issuedId = code.id;
            $('#feishu-command').value = code.command;
            $('#feishu-expiry').textContent = `有效期至 ${new Date(code.expiresAt).toLocaleTimeString('zh-CN')}，请勿发送到群聊。`;
            $('#feishu-issued').hidden = false;
            await refresh();
        });
    });
    return {refresh};
}
