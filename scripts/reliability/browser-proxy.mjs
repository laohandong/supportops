import {createServer, request as httpRequest} from 'node:http';

/** 测试专用透明代理：转发真实 HTTP，只中断已经收到的 SSE 数据；不进入 JAR。 */
export async function browserProxy(base, token) {
  const records = [], controls = {dropStreams: 0};
  const upstream = new URL(base);
  const server = createServer(async (request, response) => {
    if (request.url === '/__rd/control') {
      if (request.headers['x-rd-control'] !== token) { response.writeHead(403).end(); return; }
      const parts = []; for await (const part of request) parts.push(part);
      const input = JSON.parse(Buffer.concat(parts).toString());
      controls.dropStreams = Math.max(0, Math.min(5, Number(input.dropStreams || 0)));
      response.writeHead(200, {'Content-Type': 'application/json'}).end(JSON.stringify(controls)); return;
    }
    const record = {method: request.method, path: request.url, at: new Date().toISOString()}; records.push(record);
    const headers = {...request.headers, host: upstream.host};
    if (headers.origin) {
      const origin = new URL(headers.origin);
      if (origin.hostname === '127.0.0.1' && Number(origin.port) === server.address().port) headers.origin = upstream.origin;
    }
    const forwarded = httpRequest({hostname: upstream.hostname, port: upstream.port, path: request.url, method: request.method, headers}, incoming => {
      record.status = incoming.statusCode;
      response.writeHead(incoming.statusCode, incoming.headers);
      const drop = controls.dropStreams > 0 && /\/api\/runs\/[a-f0-9-]+\/stream\?/.test(request.url);
      if (drop) controls.dropStreams--;
      let delivered = false;
      incoming.on('data', bytes => {
        if (response.destroyed || delivered) return;
        response.write(bytes);
        if (drop) {
          record.receivedBackendBytes = bytes.length; record.droppedAfterForwarding = true;
          delivered = true; response.end(); incoming.destroy(); forwarded.destroy();
        }
      });
      incoming.on('end', () => { if (!response.destroyed) response.end(); });
      incoming.on('error', () => { if (!response.destroyed) response.destroy(); });
    });
    forwarded.on('error', () => { if (!response.headersSent) response.writeHead(502); if (!response.destroyed) response.end(); });
    response.on('close', () => forwarded.destroy());
    request.pipe(forwarded);
  });
  await new Promise(done => server.listen(0, '127.0.0.1', done));
  return {server, records, base: `http://127.0.0.1:${server.address().port}`,
    close: () => new Promise(done => { server.close(done); server.closeAllConnections(); })};
}
