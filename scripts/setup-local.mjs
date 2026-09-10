import {constants} from 'node:fs';
import {copyFile, mkdir} from 'node:fs/promises';
import {dirname, resolve} from 'node:path';
import {fileURLToPath, pathToFileURL} from 'node:url';

const root = resolve(dirname(fileURLToPath(import.meta.url)), '..');

/** 创建 Compose 对应的本地配置；排他复制确保重复运行不覆盖用户配置。 */
export async function setupLocal(directory = root) {
    const destination = resolve(directory, '.local/model.env');
    await mkdir(dirname(destination), {recursive: true});
    try {
        await copyFile(resolve(directory, 'infrastructure/local.env.example'), destination, constants.COPYFILE_EXCL);
        return true;
    } catch (error) {
        if (error.code === 'EEXIST') {
            return false;
        }
        throw new Error('无法创建本地配置，请检查模板文件及目录权限。');
    }
}

if (process.argv[1] && import.meta.url === pathToFileURL(resolve(process.argv[1])).href) {
    if (process.argv.length > 2) {
        console.error('用法：node scripts/setup-local.mjs');
        process.exitCode = 1;
    } else {
        const created = await setupLocal();
        console.log(created ? '已创建 .local/model.env，可连接默认 Compose 依赖。' : '已保留现有 .local/model.env；请自行核对存储地址与凭据。');
    }
}
