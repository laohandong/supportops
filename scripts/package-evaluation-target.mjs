import {mkdir, copyFile, writeFile, access, readFile, readdir} from 'node:fs/promises';
import path from 'node:path';
import {execFileSync} from 'node:child_process';
import {pathToFileURL} from 'node:url';
import {jarResources} from './lib/jar-resources.mjs';
import {createHash} from 'node:crypto';
import {fileURLToPath} from 'node:url';

/** 记录打包时观察到的公开构建输入；不能据此宣称现有 JAR 由这些字节构建。 */
async function sourceSnapshot() {
  const root=fileURLToPath(new URL('..',import.meta.url)),entries=[];
  async function visit(relative) {
    const absolute=path.join(root,relative);
    for(const entry of (await readdir(absolute,{withFileTypes:true})).sort((a,b)=>a.name.localeCompare(b.name,'en'))) {
      if(entry.isSymbolicLink())throw new Error('SOURCE_INPUT_SYMLINK');
      const name=relative+'/'+entry.name;
      if(entry.isDirectory())await visit(name);
      else if(entry.isFile())entries.push({path:name,sha256:createHash('sha256').update(await readFile(path.join(root,name))).digest('hex')});
    }
  }
  for(const directory of ['src/main','examples/knowledge','.mvn'])await visit(directory);
  for(const name of ['pom.xml','mvnw','mvnw.cmd'])entries.push({path:name,sha256:createHash('sha256').update(await readFile(path.join(root,name))).digest('hex')});
  entries.sort((a,b)=>a.path.localeCompare(b.path,'en'));
  return {sha256:createHash('sha256').update(JSON.stringify(entries)).digest('hex'),files:entries};
}

/** 将实际 JAR 打包为不可覆盖的版本化被测目标；不读取本地运行配置。 */
export async function packageTarget({jar, output}) {
  const source = path.resolve(jar), destination = path.resolve(output);
  try { await access(destination); throw new Error('TARGET_OUTPUT_EXISTS'); } catch (error) { if (error.code !== 'ENOENT') throw error; }
  const actual = await jarResources(source);
  const snapshot = await sourceSnapshot();
  let head = null, dirty = null;
  try {
    const cwd = fileURLToPath(new URL('..', import.meta.url));
    head = execFileSync('git', ['rev-parse', 'HEAD'], {cwd, encoding: 'utf8', windowsHide: true}).trim();
    dirty = Boolean(execFileSync('git', ['status', '--porcelain'], {cwd, encoding: 'utf8', windowsHide: true}).trim());
  } catch {}
  const descriptor = {
    namespace: 'supportops-evaluation-target', schemaVersion: 1,
    targetId: `supportops-${actual.jarSha256.slice(0, 16)}`, adapterId: 'SUPPORTOPS_V1', adapterVersion: '1.0.0',
    jar: 'application.jar', jarSha256: actual.jarSha256, resources: actual.resources,
    chunkSize: 100, overlap: 10,
    helperMain: 'io.supportops.migration.IsolatedKnowledgeWorkspace',
    submitIdempotency: 'UNSUPPORTED_STOP_ON_UNKNOWN',
    scenarios: ['unavailable', 'credentials', 'migration', 'stale-docs', 'insufficient', 'injection'],
    buildProvenance: 'UNKNOWN', sourceHead: head, sourceDirty: dirty,
    sourceSnapshotSha256: snapshot.sha256, sourceSnapshotFileCount: snapshot.files.length,
    createdAt: new Date().toISOString()
  };
  await mkdir(destination, {recursive: true});
  await copyFile(source, path.join(destination, 'application.jar'));
  await writeFile(path.join(destination, 'target.json'), JSON.stringify(descriptor, null, 2) + '\n', {flag: 'wx'});
  await writeFile(path.join(destination, 'source-snapshot.json'), JSON.stringify(snapshot, null, 2) + '\n', {flag:'wx'});
  return descriptor;
}

if (process.argv[1] && import.meta.url === pathToFileURL(path.resolve(process.argv[1])).href) {
  const args = process.argv.slice(2);
  if (args.length !== 4 || args[0] !== '--jar' || args[2] !== '--output') {
    process.stderr.write('Usage: node scripts/package-evaluation-target.mjs --jar <built.jar> --output <new-directory>\n');
    process.exitCode = 2;
  } else {
    try { const target = await packageTarget({jar: args[1], output: args[3]}); process.stdout.write(JSON.stringify({targetId: target.targetId, jarSha256: target.jarSha256, buildProvenance: target.buildProvenance}) + '\n'); }
    catch (error) { process.stderr.write(error.message + '\n'); process.exitCode = 1; }
  }
}
