/** 原工作台中的企业接入页；所有状态来自真实服务，不将配置成功当作连接通过。 */
export function initializeEnterprise({api, el, $, isAdmin, scope, userId, storage}) {
    let resources = [], selected = null, applications = [], targets = [], busy = false;
    let secretCommand = null;
    let policy = null, editingApplication = null, credentialSourceRevisionId = null;
    const storageKey = `supportops.enterprise.pending.v1.${scope}.${userId}`;
    const stateLabel = value => ({ACTIVE:'可用',DISABLED:'已停用',ARCHIVED:'已归档',DRAFT:'草稿',ENABLED:'已启用',NOT_TESTED:'未测试',PASSED:'测试通过',FAILED:'测试失败',STALE:'测试已过期',REVOKED:'已撤销',EXPIRED:'已过期'}[value] || value);
    const feedback = (message, error = false) => { $('#enterprise-feedback').textContent = message; $('#enterprise-feedback').classList.toggle('error', error); };
    const fail = error => feedback(error.message || '操作失败，请查询原请求状态。', true);
    const pending = () => {
        const raw = storage.getItem(storageKey);
        if (!raw) return null;
        const value = JSON.parse(raw);
        if (value.scope !== scope || value.userId !== userId || !value.path.startsWith('/enterprise/') || !/^[a-f0-9-]{36}$/.test(value.requestId)) throw new Error('原请求身份不匹配，请保留记录核查。');
        return value;
    };
    const drawPending = () => { const value = pending(); $('#enterprise-recovery').hidden = !value; $('#enterprise-original-request').textContent = value ? value.requestId : ''; };
    const perform = async (path, body) => {
        if (pending()) throw new Error('原请求尚未确认，请先查询或重试原请求。');
        const requestId = crypto.randomUUID();
        const original = {...body, requestId};
        const protectedBody = {...original};
        const hasSecret = Object.hasOwn(protectedBody, 'bearerSecret');
        if (hasSecret) delete protectedBody.bearerSecret;
        const command = {scope,userId,path,requestId,body:protectedBody,hasSecret};
        // 秘密仅在当前页面内存保留。刷新后通过原回执恢复，不把秘密写浏览器存储。
        if (hasSecret) secretCommand = {requestId,body:original};
        storage.setItem(storageKey, JSON.stringify(command)); drawPending();
        let result;
        try { result = await api(path,{method:'POST',body:original,signal:AbortSignal.timeout(60000)}); }
        catch (error) {
            if (error.status >= 400 && error.status < 500) {
                storage.removeItem(storageKey); secretCommand = null; drawPending();
                throw error;
            }
            throw new Error('提交结果尚未确认。请查询原请求；读取失败不会创建新请求。' + (error.message ? ` ${error.message}` : ''));
        }
        // 收到成功业务响应即确认同一事务提交；避免随后读失败丢失一次性秘密。
        storage.removeItem(storageKey); secretCommand = null; drawPending();
        return result;
    };
    const task = async action => {
        if (busy) return; busy = true;
        const controls = Array.from($('#enterprise-content').querySelectorAll('button')).map(button => [button,button.disabled]);
        controls.forEach(([button]) => button.disabled = true);
        try { await action(); } catch (error) { fail(error); }
        finally { busy = false; controls.forEach(([button,disabled]) => button.disabled = disabled); $('#enterprise-resource-submit').disabled = selected?.status === 'ARCHIVED'; }
    };
    const options = (selector, values, prompt) => {
        const node = $(selector); const previous = node.value;
        node.replaceChildren(new Option(prompt,''));
        values.forEach(value => node.append(new Option(value.name,value.id)));
        if (values.some(value => value.id === previous)) node.value = previous;
    };
    const refreshTargets = async () => {
        targets = await api('/diagnosis-targets');
        const node = $('#diagnosis-environment'), previous = node.value;
        const historical = node.disabled ? Array.from(node.options).find(option => option.value === previous) : null;
        node.replaceChildren(new Option(targets.length ? '请选择诊断环境' : '暂无可用环境，请联系管理员',''));
        targets.forEach(value => node.append(new Option(`${value.projectName} / ${value.systemName} / ${value.environmentName} · ${value.productVersion}`,value.environmentId)));
        if (targets.some(value => value.environmentId === previous)) node.value = previous;
        else if (historical && previous) { node.append(new Option(historical.textContent,previous)); node.value = previous; }
        else if (targets.length === 1 && targets[0].environmentCode === 'demo' && targets[0].systemCode === 'orderbridge') node.value = targets[0].environmentId;
        node.dispatchEvent(new Event('change'));
    };
    const edit = resource => {
        selected = resource;
        $('#enterprise-editor-heading').textContent = resource.id ? `${resource.name} · ${stateLabel(resource.status)}` : `新建${{PROJECT:'项目',SYSTEM:'系统',ENVIRONMENT:'环境'}[resource.kind]}`;
        $('#enterprise-kind').value = resource.kind; $('#enterprise-id').value = resource.id || '';
        $('#enterprise-code').value = resource.code || ''; $('#enterprise-code').readOnly = !!resource.id;
        $('#enterprise-name').value = resource.name || ''; $('#enterprise-status').value = resource.status || 'ACTIVE';
        $('#enterprise-product-key').value = resource.productKey || ''; $('#enterprise-product-key').readOnly = !!resource.id;
        $('#enterprise-product-version').value = resource.productVersion || '';
        const parents = resources.filter(value => value.kind === (resource.kind === 'SYSTEM' ? 'PROJECT' : 'SYSTEM') && value.status === 'ACTIVE');
        options('#enterprise-parent',parents,'请选择所属资源'); $('#enterprise-parent').value = resource.parentId || '';
        $('#enterprise-parent').disabled = !!resource.id;
        $('#enterprise-parent-field').hidden = resource.kind === 'PROJECT';
        $('#enterprise-product-field').hidden = resource.kind !== 'SYSTEM'; $('#enterprise-version-field').hidden = resource.kind !== 'ENVIRONMENT';
        $('#enterprise-environment-settings').hidden = resource.kind !== 'ENVIRONMENT' || !resource.id;
        $('#enterprise-data-policy').hidden = resource.kind !== 'SYSTEM' || !resource.id;
        if (resource.kind === 'SYSTEM' && resource.id) {
            const systemId = resource.id;
            api(`/enterprise/data-policy?systemId=${systemId}`).then(value => {
                if (selected?.id !== systemId) return;
                policy = value;
                $('#enterprise-document-chat').checked = value.chatAllowed;
                $('#enterprise-document-embedding').checked = value.embeddingAllowed;
            }).catch(fail);
        }
        $('#enterprise-resource-submit').disabled = resource.status === 'ARCHIVED';
        if (resource.kind === 'ENVIRONMENT' && resource.id) loadConnectors().catch(fail);
        renderTree();
    };
    const renderTree = () => {
        const tree = $('#enterprise-tree'); tree.replaceChildren();
        const append = (value,className) => {
            const li=el('li'), button=el('button',`${value.name} · ${stateLabel(value.status)}`,'secondary');
            button.type='button'; button.className += ` ${className}`; button.setAttribute('aria-pressed',String(selected?.id === value.id));
            button.onclick=()=>edit(value); li.append(button); tree.append(li);
        };
        resources.filter(value=>value.kind==='PROJECT').forEach(project=>{
            append(project,''); resources.filter(value=>value.kind==='SYSTEM' && value.parentId===project.id).forEach(system=>{
                append(system,'enterprise-system'); resources.filter(value=>value.kind==='ENVIRONMENT' && value.parentId===system.id).forEach(environment=>append(environment,'enterprise-environment'));
            });
        });
        if (!resources.length) tree.append(el('li','尚无项目，请先创建项目。','muted'));
    };
    const loadConnectors = async () => {
        const environment = selected;
        const list = await api(`/enterprise/connectors?environmentId=${environment.id}`);
        if (selected?.id !== environment.id) return;
        credentialSourceRevisionId = list.find(value=>value.credentialId)?.id || null;
        const container=$('#enterprise-connectors'); container.replaceChildren();
        list.forEach(value=>{
            const row=el('article',undefined,'enterprise-revision'); row.append(el('strong',`修订 ${value.revision} · ${value.type}`));
            const states=el('div',undefined,'enterprise-state'); states.append(el('span',stateLabel(value.status)),el('span',stateLabel(value.testStatus))); row.append(states);
            if(value.errorCode) row.append(el('p',`失败原因：${value.errorCode}`));
            if(value.testedAt) row.append(el('p',`测试时间：${new Date(value.testedAt).toLocaleString('zh-CN')}`,'muted'));
            const actions=el('div',undefined,'enterprise-actions');
            if(value.type==='STANDARD_REST') {
                const test=el('button','测试此修订','secondary'); test.type='button'; test.onclick=()=>task(async()=>{
                    const result=await perform('/enterprise/connectors/test',{revisionId:value.id,expectedVersion:null});
                    feedback(result.testStatus==='PASSED'?'固定能力测试通过。':`测试失败：${result.errorCode}`,result.testStatus!=='PASSED'); await refresh();
                }); actions.append(test);
                const activate=el('button','启用此修订','primary'); activate.type='button'; activate.disabled=value.testStatus!=='PASSED';
                activate.onclick=()=>task(async()=>{await perform('/enterprise/connectors/enable',{revisionId:value.id,expectedVersion:selected.version}); feedback('连接已启用。'); await refresh();}); actions.append(activate);
            }
            row.append(actions); container.append(row);
        });
        if(!list.length) container.append(el('p','尚未配置连接。保存草稿后测试，通过后再启用。','muted'));
    };
    const refresh = async () => {
        if(!isAdmin) return refreshTargets();
        const values=await Promise.all([api('/enterprise/resources'),api('/enterprise/applications'),api('/users')]);
        resources=values[0]; applications=values[1];
        const systems=resources.filter(value=>value.kind==='SYSTEM'&&value.status==='ACTIVE');
        options('#document-system',systems,'请选择所属系统');if(systems.length===1&&!$('#document-system').value)$('#document-system').value=systems[0].id;
        options('#search-system',systems,'请选择所属系统');if(systems.length===1&&!$('#search-system').value)$('#search-system').value=systems[0].id;
        options('#feishu-environment',resources.filter(value=>value.kind==='ENVIRONMENT'&&value.status==='ACTIVE'),'请选择绑定环境');
        const projects=resources.filter(value=>value.kind==='PROJECT'&&value.status==='ACTIVE');
        options('#memory-project',projects,'请选择所属项目');if(projects.length===1&&!$('#memory-project').value)$('#memory-project').value=projects[0].id;
        $('#memory-project').onchange=()=>{options('#memory-system',resources.filter(value=>value.kind==='SYSTEM'&&value.parentId===$('#memory-project').value),'整个项目');$('#memory-system').onchange();};
        $('#memory-system').onchange=()=>{options('#memory-environment',resources.filter(value=>value.kind==='ENVIRONMENT'&&value.parentId===$('#memory-system').value),'整个系统或项目');};
        $('#memory-project').onchange();
        renderTree(); options('#enterprise-member-user',values[2].map(value=>({...value,name:value.username})),'请选择用户');
        options('#enterprise-application-project',resources.filter(value=>value.kind==='PROJECT'&&value.status==='ACTIVE'),'请选择项目');
        options('#enterprise-key-application',applications.filter(value=>value.status==='ACTIVE'),'请选择应用');
        renderApplications();
        if(selected?.id) { const current=resources.find(value=>value.id===selected.id); if(current) edit(current); }
        await refreshTargets(); drawPending();
    };
    const renderApplications = () => {
        const container=$('#enterprise-applications'); container.replaceChildren();
        applications.forEach(value=>{
            const row=el('article',undefined,'enterprise-revision'); row.append(el('strong',`${value.name} · ${stateLabel(value.status)}`),el('p',`权限：${value.permissions.join('、') || '无'}；授权环境：${value.environmentIds.length}；额度 ${value.submitPerMinute}/分钟提交 · ${value.readPerMinute}/分钟读取 · ${value.maxActive}项活动任务`,'muted'));
            const button=el('button',value.status==='ACTIVE'?'停用应用':'启用应用','secondary'); button.type='button'; button.disabled=value.status==='ARCHIVED';
            button.onclick=()=>task(async()=>{await perform('/enterprise/applications',{...value,requestId:undefined,status:value.status==='ACTIVE'?'DISABLED':'ACTIVE',expectedVersion:value.version,version:undefined}); await refresh(); feedback('应用状态已更新。');});
            const editButton=el('button','修改权限与环境','secondary');editButton.type='button';editButton.disabled=value.status==='ARCHIVED';
            editButton.onclick=()=>{
                editingApplication=value;
                $('#enterprise-application-project').value=value.projectId;
                $('#enterprise-application-project').disabled=true;
                $('#enterprise-application-project').onchange();
                $('#enterprise-application-code').value=value.code;$('#enterprise-application-code').readOnly=true;
                $('#enterprise-application-name').value=value.name;
                $('#enterprise-submit-limit').value=value.submitPerMinute;$('#enterprise-read-limit').value=value.readPerMinute;$('#enterprise-active-limit').value=value.maxActive;
                document.querySelectorAll('[name=enterprise-permission]').forEach(node=>node.checked=value.permissions.includes(node.value));
                Array.from($('#enterprise-application-environments').options).forEach(option=>option.selected=value.environmentIds.includes(option.value));
                $('#enterprise-application-form').scrollIntoView({block:'nearest'});$('#enterprise-application-name').focus();
            };
            row.append(button,editButton); container.append(row);
        });
    };
    if(isAdmin) {
        $('#enterprise-data-policy').onsubmit=event=>{event.preventDefault();task(async()=>{
            if(!policy||policy.systemId!==selected?.id)throw new Error('请等待策略读取完成。');
            policy=await perform('/enterprise/data-policy',{systemId:selected.id,chatAllowed:$('#enterprise-document-chat').checked,
                embeddingAllowed:$('#enterprise-document-embedding').checked,expectedVersion:policy.version});
            feedback('资料外发策略已更新，运行中的任务不会因此扩大原许可。');
        });};
        document.querySelectorAll('[data-enterprise-create]').forEach(button=>button.onclick=()=>edit({kind:button.dataset.enterpriseCreate,status:'ACTIVE',parentId:selected?.id}));
        $('#enterprise-refresh').onclick=()=>task(refresh);
        $('#enterprise-resource-form').onsubmit=event=>{event.preventDefault();task(async()=>{
            const kind=$('#enterprise-kind').value;
            const result=await perform(`/enterprise/${{PROJECT:'projects',SYSTEM:'systems',ENVIRONMENT:'environments'}[kind]}`,{id:$('#enterprise-id').value||null,parentId:$('#enterprise-parent').value||null,code:$('#enterprise-code').value,name:$('#enterprise-name').value,productKey:$('#enterprise-product-key').value||null,productVersion:$('#enterprise-product-version').value||null,status:$('#enterprise-status').value,expectedVersion:selected?.id?selected.version:null});
            selected=result; await refresh(); feedback('资源已保存。');
        });};
        $('#enterprise-secret-action').onchange=()=>{const replace=$('#enterprise-secret-action').value==='REPLACE';$('#enterprise-bearer').required=replace;$('#enterprise-bearer').disabled=!replace;if(!replace)$('#enterprise-bearer').value='';};
        $('#enterprise-connector-form').onsubmit=event=>{event.preventDefault();task(async()=>{
            const capabilities=['APP_INFO',...Array.from(document.querySelectorAll('[name=enterprise-capability]:checked')).map(node=>node.value)];
            const modelData=['APP_INFO',...Array.from(document.querySelectorAll('[name=enterprise-model-data]:checked')).map(node=>node.value)];
            await perform('/enterprise/connectors',{environmentId:selected.id,type:'STANDARD_REST',config:{baseUrl:$('#enterprise-base-url').value,allowedAddresses:$('#enterprise-addresses').value.split(/[,\s]+/).filter(Boolean),capabilities,modelData,allowLocalHttp:$('#enterprise-local-http').checked},bearerSecret:$('#enterprise-secret-action').value==='REPLACE'?$('#enterprise-bearer').value:null,secretAction:$('#enterprise-secret-action').value,credentialSourceRevisionId:$('#enterprise-secret-action').value==='KEEP'?credentialSourceRevisionId:null});
            $('#enterprise-bearer').value=''; feedback('新草稿已保存。请测试后启用。'); await loadConnectors();
        });};
        $('#enterprise-member-form').onsubmit=event=>{event.preventDefault();task(async()=>{
            const kind=$('#enterprise-grant-kind').value;
            const parentSystem=resources.find(value=>value.id===selected.parentId);
            const resourceId=kind==='PROJECT'?parentSystem.parentId:selected.id;
            await perform(`/enterprise/${kind==='PROJECT'?'project-members':'environment-grants'}`,{resourceId,userId:$('#enterprise-member-user').value,granted:$('#enterprise-granted').value==='true'});
            feedback('授权关系已更新，后续请求立即重新核对。'); await refreshTargets();
        });};
        $('#enterprise-application-form').onsubmit=event=>{event.preventDefault();task(async()=>{
            const projectId=$('#enterprise-application-project').value;
            const environments=resources.filter(value=>value.kind==='ENVIRONMENT'&&resources.find(system=>system.id===value.parentId)?.parentId===projectId&&value.status==='ACTIVE');
            const chosen=Array.from($('#enterprise-application-environments').selectedOptions).map(option=>option.value);
            if(chosen.some(id=>!environments.some(value=>value.id===id))) throw new Error('请从所属项目中选择环境。');
            await perform('/enterprise/applications',{id:editingApplication?.id||null,projectId,code:$('#enterprise-application-code').value,name:$('#enterprise-application-name').value,status:editingApplication?.status||'ACTIVE',permissions:Array.from(document.querySelectorAll('[name=enterprise-permission]:checked')).map(node=>node.value),environmentIds:chosen,expectedVersion:editingApplication?.version||null,submitPerMinute:Number($('#enterprise-submit-limit').value),readPerMinute:Number($('#enterprise-read-limit').value),maxActive:Number($('#enterprise-active-limit').value)});
            editingApplication=null;$('#enterprise-application-project').disabled=false;$('#enterprise-application-code').readOnly=false;
            $('#enterprise-application-form').reset();await refresh(); feedback('应用及授权已保存，可在下方签发访问密钥。');
        });};
        $('#enterprise-application-project').onchange=()=>{const project=$('#enterprise-application-project').value;const node=$('#enterprise-application-environments');node.replaceChildren(); resources.filter(value=>value.kind==='ENVIRONMENT'&&value.status==='ACTIVE'&&resources.find(system=>system.id===value.parentId)?.parentId===project).forEach(value=>node.append(new Option(value.name,value.id)));};
        $('#enterprise-key-form').onsubmit=event=>{event.preventDefault();task(async()=>{
            const result=await perform('/enterprise/applications/keys',{applicationId:$('#enterprise-key-application').value,expiresInDays:Number($('#enterprise-key-days').value),rotate:$('#enterprise-key-rotate').checked});
            $('#enterprise-secret-area').hidden=false; $('#enterprise-key-id').textContent=result.key.id;
            $('#enterprise-one-time-secret').textContent=result.secret||'原签发响应已丢失，回执不能恢复秘密。请撤销这把密钥后重新签发。';
            feedback(result.secret?'密钥已签发，请立即保存一次性原文。':'已恢复签发状态，原文不可回放。'); await loadKeys();
        });};
        const loadKeys=async()=>{const id=$('#enterprise-key-application').value;if(!id)return;const keys=await api(`/enterprise/applications/keys?applicationId=${id}`);const node=$('#enterprise-keys');node.replaceChildren();keys.forEach(key=>{const row=el('article',undefined,'enterprise-revision');row.append(el('p',`${key.id} · ${stateLabel(key.status)} · 过期 ${new Date(key.expiresAt).toLocaleString('zh-CN')}`));if(key.status==='ACTIVE'){const button=el('button','撤销此密钥','secondary');button.type='button';button.onclick=()=>task(async()=>{await perform('/enterprise/applications/keys/revoke',{keyId:key.id});await loadKeys();feedback('密钥已撤销。');});row.append(button);}node.append(row);});};
        $('#enterprise-key-application').onchange=()=>{ $('#enterprise-secret-area').hidden=true;$('#enterprise-one-time-secret').textContent='';loadKeys().catch(fail); };
        $('#enterprise-hide-secret').onclick=()=>{ $('#enterprise-secret-area').hidden=true;$('#enterprise-one-time-secret').textContent=''; };
        $('#enterprise-recover').onclick=()=>task(async()=>{const original=pending();if(!original)return;const receipt=await api(`/enterprise/requests/${original.requestId}`);if(receipt.requestId!==original.requestId)throw new Error('回执身份不匹配。');storage.removeItem(storageKey);secretCommand=null;drawPending();feedback('原请求已确认。签发秘密不能回放；请核对资源或密钥状态。');await refresh();});
        $('#enterprise-retry').onclick=()=>task(async()=>{const original=pending();if(!original)return;const body=original.hasSecret?secretCommand?.requestId===original.requestId?secretCommand.body:null:original.body;if(!body)throw new Error('此请求的秘密已离开内存。请查询原回执，不能换键重新写入。');const result=await api(original.path,{method:'POST',body});if(result.secret){$('#enterprise-one-time-secret').textContent=result.secret;$('#enterprise-key-id').textContent=result.key.id;$('#enterprise-secret-area').hidden=false;}storage.removeItem(storageKey);secretCommand=null;drawPending();await refresh();feedback('原请求已确认。');});
        try { drawPending(); } catch (error) { feedback('浏览器无法保存固定请求，管理写入已阻止。请允许本地存储后刷新。',true); }
        edit({kind:'PROJECT',status:'ACTIVE'});
    }
    (isAdmin?refresh():refreshTargets()).catch(error=>{ $('#diagnosis-target-note').textContent='目标读取失败，请刷新页面后重试。'; });
    return {setActive(value){if(value&&isAdmin)refresh().catch(fail);},refreshTargets,logout(){secretCommand=null;$('#enterprise-one-time-secret').textContent='';}};
}
