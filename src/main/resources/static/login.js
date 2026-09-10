const form = document.querySelector('#login-form');
const button = document.querySelector('#login-submit');
const error = document.querySelector('#login-error');
let setupRequired = false;
const messages = {
    INVALID_CREDENTIALS: '用户名或密码不正确，请重新输入。',
    SETUP_COMPLETED: '管理员已创建，请刷新页面后登录。',
    LOGIN_RATE_LIMITED: '登录尝试过多，请等待一分钟后重试。',
    INVALID_USERNAME: '用户名需为 3–32 位字母、数字或下划线。',
    INVALID_PASSWORD: '密码需为 12–128 个字符。'
};
try {
    const response = await fetch('/api/auth/setup');
    if (!response.ok) throw new Error('无法读取账号状态，请刷新页面重试。');
    setupRequired = (await response.json()).setupRequired;
    if (setupRequired) {
        document.querySelector('#login-title').textContent = '创建首个管理员';
        document.querySelector('#login-description').textContent = '首次使用需先创建管理员。之后可在用户管理中为同事创建账号。';
        document.querySelector('#password').autocomplete = 'new-password';
        document.querySelector('#password').minLength = 12;
        document.querySelector('#password-hint').textContent = '12–128 个字符。';
    }
    button.textContent = setupRequired ? '创建管理员并进入' : '登录';
    button.disabled = false;
} catch (failure) {
    error.textContent = failure.message; error.hidden = false;
}
form.addEventListener('submit', async event => {
    event.preventDefault();
    button.disabled = true; error.hidden = true;
    try {
        const response = await fetch('/api/auth/' + (setupRequired ? 'setup' : 'login'), {
            method: 'POST', headers: {'Content-Type': 'application/json', 'X-SupportOps-Request': '1'},
            body: JSON.stringify({username: form.username.value, password: form.password.value})
        });
        if (!response.ok) {
            const data = await response.json();
            throw new Error(messages[data.error] || '操作未完成，请检查输入和本地服务后重试。');
        }
        form.password.value = '';
        location.replace('/');
    } catch (failure) {
        error.textContent = failure.message; error.hidden = false;
    } finally {
        button.disabled = false;
    }
});
