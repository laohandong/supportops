import {execFileSync} from 'node:child_process';
import {mkdir, mkdtemp, rm, writeFile} from 'node:fs/promises';
import {tmpdir} from 'node:os';
import {basename, dirname, join, resolve} from 'node:path';

/** 创建独占临时目录；清理前核对父目录和名称，只回收本用例资源。 */
export async function fixture(context) {
    const parent = resolve(tmpdir());
    const root = await mkdtemp(join(parent, 'supportops-ci-'));
    context.after(async () => {
        if (dirname(root) !== parent || !basename(root).startsWith('supportops-ci-')) {
            throw Error('UNEXPECTED_FIXTURE_PATH');
        }
        await rm(root, {recursive: true, force: true});
    });
    return root;
}

/** 在测试自有目录写入合成文件。 */
export async function put(root, file, text) {
    const destination = join(root, file);
    await mkdir(dirname(destination), {recursive: true});
    await writeFile(destination, text);
}

/** Git 用例不访问远端，也不加载用户提交钩子或签名设置。 */
export function git(root, ...args) {
    return execFileSync('git', args, {cwd: root, encoding: 'utf8', windowsHide: true, stdio: ['ignore', 'pipe', 'pipe']}).trim();
}

/** 只在合成仓库提交夹具，不改真实项目的 Git 配置。 */
export function commit(root) {
    git(root, 'add', '--all');
    git(root, '-c', 'user.name=CI fixture', '-c', 'user.email=ci@example.invalid', '-c', 'commit.gpgsign=false', '-c', 'core.hooksPath=' + join(root, 'no-hooks'), 'commit', '-m', 'Synthetic change');
    return git(root, 'rev-parse', 'HEAD');
}
