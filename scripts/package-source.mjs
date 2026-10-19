import {access, copyFile, mkdir, readdir, readFile, writeFile, lstat} from 'node:fs/promises';
import {createHash} from 'node:crypto';
import {resolve, dirname, join, relative} from 'node:path';
import {fileURLToPath, pathToFileURL} from 'node:url';

/** 从明确源码白名单形成独立根，拒绝覆盖、符号链接和私密运行目录。 */
export async function packageSource(output) {
  if (!output) throw Error('SOURCE_OUTPUT_REQUIRED');
  const root = resolve(dirname(fileURLToPath(import.meta.url)), '..'), destination = resolve(output);
  if (destination === root || root.startsWith(destination + '/') || root.startsWith(destination + '\\')) throw Error('SOURCE_OUTPUT_CONTAINS_PROJECT');
  try { await access(destination); throw Error('SOURCE_OUTPUT_EXISTS'); } catch (error) { if (error.code !== 'ENOENT') throw error; }
  const project = (await readFile(join(root,'pom.xml'),'utf8')).includes('<artifactId>agent-eval</artifactId>') ? 'agent-eval' : 'supportops';
  const directories = ['.github','.mvn','src','scripts','docs','infrastructure','contracts', ...(project==='agent-eval'?['runner','datasets']:['examples','skills','evaluation'])];
  const names = ['AGENTS.md','README.md','ROADMAP.md','LICENSE','pom.xml','package.json','package-lock.json','mvnw','mvnw.cmd','.gitignore','.env.example','docker-compose.yml','providers.example.yml'];
  for (const name of directories) {
    const included = join(root,name);
    const comparedOutput = process.platform === 'win32' ? destination.toLowerCase() : destination;
    const comparedInput = process.platform === 'win32' ? included.toLowerCase() : included;
    if (comparedOutput === comparedInput || comparedOutput.startsWith(comparedInput + '/') || comparedOutput.startsWith(comparedInput + '\\')) throw Error('SOURCE_OUTPUT_INSIDE_INCLUDED_DIRECTORY');
  }
  // 根构建target不在白名单；不能排除源码中名为target的业务包。
  const excluded = new Set(['.git','.local','.cache','node_modules','.env']);
  // 联合拆分记录留在开发目录，SupportOps 交付只包含自身的验证与接口文档。
  const excludedPaths = new Set(project === 'supportops' ? [
    'docs/plans/agent-evaluation-split-design.md',
    'docs/plans/agent-evaluation-split-tasks.md',
    'docs/evaluation-split-acceptance-20261011.md',
    'docs/evaluation-split-evidence-index-20261011.json',
    'docs/evaluation-split-review-20261011.md',
    'docs/evaluation-split-review-evidence-20261011.json',
    'docs/evaluation-split-full-regression-20261011.md',
    'docs/evaluation-split-full-regression-evidence-20261011.json'
  ] : []);
  const files = [];
  async function copy(name) {
    if ((await lstat(join(root,name))).isSymbolicLink()) throw Error('SOURCE_SYMLINK_REFUSED');
    const source = join(root,name), target = join(destination,name), bytes = await readFile(source);
    await mkdir(dirname(target),{recursive:true}); await copyFile(source,target);
    files.push({path:name.replaceAll('\\','/'),bytes:bytes.length,sha256:createHash('sha256').update(bytes).digest('hex')});
  }
  async function visit(name) {
    if ((await lstat(join(root,name))).isSymbolicLink()) throw Error('SOURCE_SYMLINK_REFUSED');
    for (const entry of (await readdir(join(root,name),{withFileTypes:true})).sort((a,b)=>a.name.localeCompare(b.name,'en'))) {
      if (excluded.has(entry.name)) continue;
      if (entry.isSymbolicLink()) throw Error('SOURCE_SYMLINK_REFUSED');
      const child = join(name,entry.name);
      if (excludedPaths.has(child.replaceAll('\\','/'))) continue;
      if (entry.isDirectory()) await visit(child); else if (entry.isFile()) await copy(child);
    }
  }
  await mkdir(destination,{recursive:true});
  for (const name of directories) { try { await access(join(root,name)); } catch { continue; } await visit(name); }
  for (const name of names) { try { await access(join(root,name)); } catch { continue; } await copy(name); }
  files.sort((a,b)=>a.path.localeCompare(b.path,'en'));
  const manifest={namespace:'independent-project-source',schemaVersion:1,project,createdAt:new Date().toISOString(),files,
    contentSha256:createHash('sha256').update(JSON.stringify(files)).digest('hex')};
  await writeFile(join(destination,'source-manifest.json'),JSON.stringify(manifest,null,2)+'\n');
  return {project,directory:destination,fileCount:files.length,contentSha256:manifest.contentSha256};
}
if (process.argv[1] && import.meta.url===pathToFileURL(resolve(process.argv[1])).href) {
  try { if (process.argv.length!==3) throw Error('Usage: node scripts/package-source.mjs <new-directory>'); console.log(JSON.stringify(await packageSource(process.argv[2]))); }
  catch (error) { console.error(error.message); process.exitCode=1; }
}
