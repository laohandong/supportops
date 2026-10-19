import {freemem} from 'node:os';
import {statfs} from 'node:fs/promises';
import {inventory} from './resources.mjs';

/** 创建验收存储前保留至少4GiB余量，最多同时三套已标记存储（含原工作台和恢复源/目标）。 */
export async function capacityCheck({snapshot,availableBytes,directory=process.cwd(),diskAvailableBytes,minimumDiskBytes=8*1024**3}={}) {
  const observed=snapshot ?? await inventory();
  const available=availableBytes ?? freemem();
  const filesystem=diskAvailableBytes===undefined?await statfs(directory):null;
  const diskAvailable=diskAvailableBytes ?? filesystem.bavail*filesystem.bsize;
  const deployments=[...new Set(observed.containers.filter(item=>item.state==='running')
    .map(item=>item.labels?.['io.supportops.deployment']).filter(Boolean))];
  if(available<4*1024**3)throw new Error('ACCEPTANCE_MEMORY_HEADROOM_REQUIRED');
  if(deployments.length>=3)throw new Error('ACCEPTANCE_STORAGE_CAPACITY_EXCEEDED');
  if(diskAvailable<minimumDiskBytes)throw new Error('ACCEPTANCE_DISK_HEADROOM_REQUIRED');
  return {observedAt:new Date().toISOString(),availableBytes:available,diskAvailableBytes:diskAvailable,minimumDiskBytes,runningDeployments:deployments,maxAfterCreate:3};
}
