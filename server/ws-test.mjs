import { WebSocket } from 'ws';
const url = process.argv[2] || 'ws://localhost:8095';
const ws = new WebSocket(url);
let done = false;
const timer = setTimeout(() => { if(!done){console.log('TIMEOUT 无响应'); process.exit(1);} }, 8000);
ws.on('open', () => {
  console.log('WS 已连接');
  ws.send(JSON.stringify({ type: 'create', code: '9999', userId: 'probe_host', userName: 'probe' }));
});
ws.on('message', (d) => {
  const m = JSON.parse(d.toString());
  console.log('收到:', JSON.stringify(m));
  if (m.type === 'created') { done = true; clearTimeout(timer); ws.close(); process.exit(0); }
  if (m.type === 'error' || m.type === 'rejected') { done = true; clearTimeout(timer); console.log('创建被拒'); process.exit(2); }
});
ws.on('error', (e) => { console.log('WS 错误:', e.message); process.exit(3); });
