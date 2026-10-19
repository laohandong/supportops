/** 新命令规范与Java契约一致，正文在发送前只规范化一次。 */
export function canonicalCommand(value) {
    const normalize = item => typeof item === 'string' ? item.replace(/\r\n?/g, '\n').replace(/^[ \t\n\r]+|[ \t\n\r]+$/g, '').normalize('NFC')
        : Array.isArray(item) ? item.map(normalize)
        : item && typeof item === 'object' ? Object.fromEntries(Object.keys(item).sort().map(key => [key, normalize(item[key])])) : item;
    return JSON.stringify(normalize(value));
}

/** 按部署、账号隔离一个待确认命令；存储失效时当前页面仍保留原命令。 */
export function createFixedCommandStore(storage, scope, userId) {
    const key = `supportops.outcome-library.pending.v1.${scope}.${userId}`;
    let memory = null, limited = false;
    const immutable = value => Object.freeze({...value});
    const decode = source => {
        const value = JSON.parse(source);
        if (value.scope !== scope || value.userId !== userId || value.method !== 'POST'
            || !/^[a-f0-9-]{36}$/.test(value.requestId || '') || typeof value.serializedBody !== 'string'
            || !value.path.startsWith('/runs/') && !value.path.startsWith('/evaluations/')) throw new Error('恢复记录损坏，请保留原记录并核查。');
        const body = JSON.parse(value.serializedBody);
        if (body.requestId !== value.requestId) throw new Error('恢复记录身份不匹配。');
        return immutable(value);
    };
    const read = () => {
        if (limited) return memory;
        let raw;
        try { raw = storage.getItem(key); } catch { limited = true; return memory; }
        if (!raw) { memory = null; return null; }
        memory = decode(raw);
        return memory;
    };
    return {
        key, read,
        get limited() { return limited; },
        freeze(path, kind, targetId, body) {
            if (read()) throw new Error('原请求尚未确认，请先查询或重试原命令。');
            const command = immutable({scope, userId, method: 'POST', path, kind, targetId,
                requestId: body.requestId, serializedBody: canonicalCommand(body)});
            if (new TextEncoder().encode(command.serializedBody).length > 262144) throw new Error('正文超过256KiB。');
            memory = command;
            try { storage.setItem(key, JSON.stringify(command)); } catch { limited = true; }
            return command;
        },
        confirm(receipt) {
            const command = read();
            if (!command || receipt?.requestId !== command.requestId || !Number.isInteger(receipt.httpStatus) || receipt.httpStatus < 100
                || (command.kind === 'EVALUATION_EXPORT' ? false : command.kind === 'SAVE' ? receipt.runId !== command.targetId : receipt.kind !== command.kind || receipt.targetId !== command.targetId)) return false;
            try { storage.removeItem(key); } catch { limited = true; }
            memory = null;
            return true;
        },
        logout() {
            try { storage.removeItem(key); } catch { limited = true; }
            memory = null;
        }
    };
}

/** 命令发送、查询只确认同一个原键；错误和404不会释放命令。 */
export function initializeFixedCommands({api, scope, userId, storage = globalThis.localStorage, onState = () => {}}) {
    const store = createFixedCommandStore(storage, scope, userId);
    let closed = false;
    const current = async () => {
        const identity = await api('/auth/me');
        const deployed = await api('/auth/command-scope');
        if (closed || identity.id !== userId || deployed.scope !== scope) throw new Error('账号或部署已变化，原请求保持隔离，请重新登录读取。');
    };
    const lookup = command => command.kind === 'EVALUATION_EXPORT' ? '/evaluation-exports/requests/' + command.requestId : command.kind === 'SAVE' ? `/runs/${command.targetId}/outcome/requests/${command.requestId}` : `/evaluations/library/requests/${command.requestId}`;
    const recover = async () => {
        const command = store.read();
        if (!command) return null;
        await current();
        try {
            const receipt = await api(lookup(command), {signal: AbortSignal.timeout(15000)});
            await current();
            if (!store.confirm(receipt)) throw new Error('原回执身份未匹配，继续保留原命令。');
            onState(receipt);
            return receipt;
        } catch (error) {
            onState(null);
            throw new Error('原请求尚未确认；读取失败、401或404不能换键。请查询原请求或显式重试原命令。');
        }
    };
    const send = async command => {
        await current();
        try { await api(command.path, {method: command.method, body: JSON.parse(command.serializedBody), signal: AbortSignal.timeout(45000)}); }
        catch { /* 接口拒绝也通过原回执确认，网络错误不能证明未受理。 */ }
        return recover();
    };
    const freeze = async (path, kind, targetId, body) => {
        await current();
        const work = () => store.freeze(path, kind, targetId, body);
        // Web Locks只串行冻结，持久化槽负责响应丢失后的跨标签页保护。
        return globalThis.navigator?.locks ? navigator.locks.request(store.key, work) : work();
    };
    return {store, recover,
        async mutate(path, kind, targetId, body) { const command = await freeze(path, kind, targetId, body); onState(null); return send(command); },
        async retry() { const command = store.read(); if (!command) return null; return send(command); },
        logout() { closed = true; store.logout(); }
    };
}
