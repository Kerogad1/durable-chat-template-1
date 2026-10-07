import http from 'node:http';
import { WebSocketServer } from 'ws';
import crypto from 'node:crypto';
import fs from 'node:fs';
import path from 'node:path';

const PORT = Number(process.env.PORT || 8080);
const PAIR_FILE = path.join(process.cwd(), 'pair-code.txt');

function loadPairCode() {
  if (process.env.PAIR_CODE) return process.env.PAIR_CODE.trim().toUpperCase();

  try {
    const saved = fs.readFileSync(PAIR_FILE, 'utf8').trim();
    if (saved) return saved.toUpperCase();
  } catch {}

  const generated = crypto.randomBytes(4).toString('hex').toUpperCase();
  fs.writeFileSync(PAIR_FILE, `${generated}\n`, 'utf8');
  return generated;
}

const PAIR_CODE = loadPairCode();
const sessions = new Map();

const server = http.createServer((req, res) => {
  if (req.url === '/health') {
    res.writeHead(200, { 'content-type': 'application/json' });
    res.end(JSON.stringify({ ok: true, service: 'remote-device-poc', pairCode: PAIR_CODE }));
    return;
  }
  res.writeHead(404);
  res.end('Not found');
});

const wss = new WebSocketServer({ server });

function send(ws, message) {
  if (ws && ws.readyState === ws.OPEN) ws.send(JSON.stringify(message));
}

wss.on('connection', (ws, req) => {
  const url = new URL(req.url, `http://${req.headers.host}`);
  const role = url.searchParams.get('role');
  const code = url.searchParams.get('code');

  if (!['agent', 'controller'].includes(role) || code !== PAIR_CODE) {
    send(ws, { type: 'error', message: 'Unauthorized' });
    ws.close(1008, 'Unauthorized');
    return;
  }

  const session = sessions.get(PAIR_CODE) || {};

  if (session[role] && session[role] !== ws && session[role].readyState === session[role].OPEN) {
    session[role].close(1000, 'Replaced by a newer connection');
  }

  session[role] = ws;
  sessions.set(PAIR_CODE, session);

  console.log(`[CONNECT] role=${role} remote=${req.socket.remoteAddress}`);

  send(ws, { type: 'hello', role, pairCode: PAIR_CODE });
  if (session.agent && session.controller) {
    send(session.agent, { type: 'peer', online: true });
    send(session.controller, { type: 'peer', online: true });
  }

  ws.on('message', (raw, isBinary) => {
    const target = role === 'agent'
      ? session.controller
      : session.agent;

    if (!target || target.readyState !== target.OPEN) {
      console.log(`[${role}] NO TARGET CONNECTED`);
      return;
    }

    if (isBinary) {
      console.log(`[${role}] BINARY FRAME: ${raw.length} bytes`);
      target.send(raw, { binary: true });
      return;
    }

    console.log(`[${role}] MESSAGE:`, raw.toString());

    let msg;
    try {
      msg = JSON.parse(raw.toString());
    } catch {
      console.log(`[${role}] INVALID JSON`);
      return;
    }

    console.log(`[${role}] FORWARDING TO ${role === 'agent' ? 'controller' : 'agent'}:`, msg);
    send(target, msg);
  });

  ws.on('close', (closeCode, reason) => {
    console.log(`[CLOSE] role=${role} code=${closeCode} reason=${reason?.toString() || ''}`);
    const current = sessions.get(PAIR_CODE);
    if (!current) return;
    if (current[role] === ws) current[role] = null;
    const other = current[role === 'agent' ? 'controller' : 'agent'];
    send(other, { type: 'peer', online: false });
  });
});

server.listen(PORT, () => {
  console.log(`Remote device POC server listening on port ${PORT}`);
  console.log(`PAIR_CODE=${PAIR_CODE}`);
});
