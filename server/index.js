import http from 'node:http';
import { WebSocketServer, WebSocket } from 'ws';
import crypto from 'node:crypto';
import fs from 'node:fs';
import path from 'node:path';

const PORT = Number(process.env.PORT || 8080);
const PAIR_FILE = path.join(process.cwd(), 'pair-code.txt');

function loadPairCode() {
  if (process.env.PAIR_CODE) {
    return process.env.PAIR_CODE.trim().toUpperCase();
  }

  try {
    const saved = fs.readFileSync(PAIR_FILE, 'utf8').trim();

    if (saved) {
      return saved.toUpperCase();
    }
  } catch {}

  const generated = crypto
    .randomBytes(4)
    .toString('hex')
    .toUpperCase();

  fs.writeFileSync(
    PAIR_FILE,
    `${generated}\n`,
    'utf8'
  );

  return generated;
}

const PAIR_CODE = loadPairCode();

/*
 * جلسة واحدة لكل كود اقتران.
 *
 * controller:
 *   جهاز التحكم الحالي.
 *
 * agents:
 *   Map<deviceId, WebSocket>
 *
 * بهذا الشكل نقدر ندعم أكثر من هاتف Agent
 * بدل ما السيرفر يحتفظ بعميل واحد فقط.
 */
const sessions = new Map();

function getSession() {
  let session = sessions.get(PAIR_CODE);

  if (!session) {
    session = {
      controller: null,
      agents: new Map(),
      activeDeviceId: null
    };

    sessions.set(PAIR_CODE, session);
  }

  return session;
}

function isOpen(ws) {
  return !!ws && ws.readyState === WebSocket.OPEN;
}

function send(ws, message) {
  if (!isOpen(ws)) {
    return false;
  }

  try {
    ws.send(JSON.stringify(message));
    return true;
  } catch (error) {
    console.error('[SEND ERROR]', error);
    return false;
  }
}

function sendRaw(ws, data) {
  if (!isOpen(ws)) {
    return false;
  }

  try {
    ws.send(data, { binary: true });
    return true;
  } catch (error) {
    console.error('[SEND BINARY ERROR]', error);
    return false;
  }
}

function getDeviceList(session) {
  return Array.from(session.agents.entries()).map(
    ([deviceId, ws]) => ({
      deviceId,
      deviceName: `Android ${deviceId.slice(-4)}`,
      online: isOpen(ws)
    })
  );
}

function sendDeviceList(session) {
  if (!isOpen(session.controller)) {
    return;
  }

  const devices = getDeviceList(session);

  console.log(
    '[DEVICE LIST]',
    devices
  );

  send(
    session.controller,
    {
      type: 'device_list',
      devices
    }
  );
}

function sendError(ws, message) {
  send(
    ws,
    {
      type: 'error',
      message
    }
  );
}

const server = http.createServer(
  (req, res) => {
    if (req.url === '/health') {
      res.writeHead(
        200,
        {
          'content-type': 'application/json'
        }
      );

      res.end(
        JSON.stringify({
          ok: true,
          service: 'remote-device-poc'
        })
      );

      return;
    }

    res.writeHead(404);
    res.end('Not found');
  }
);

server.on(
  'error',
  (error) => {
    console.error(
      '[SERVER ERROR]',
      error
    );

    if (error?.code === 'EADDRINUSE') {
      console.error(
        `[SERVER ERROR] Port ${PORT} is already in use. Stop the old server/process first.`
      );
    }
  }
);

const wss = new WebSocketServer({ server });

wss.on(
  'connection',
  (ws, req) => {
    const url = new URL(
      req.url,
      `http://${req.headers.host}`
    );

    const role =
      url.searchParams.get('role');

    const code =
      url.searchParams.get('code');

    const deviceId =
      url.searchParams.get('deviceId')?.trim() || '';

    if (
      !['agent', 'controller'].includes(role) ||
      code !== PAIR_CODE
    ) {
      send(
        ws,
        {
          type: 'error',
          message: 'Unauthorized'
        }
      );

      ws.close(
        1008,
        'Unauthorized'
      );

      return;
    }

    const session = getSession();

    /*
     * Controller:
     * نسمح باتصال Controller واحد فقط.
     */
    if (role === 'controller') {
      if (
        isOpen(session.controller) &&
        session.controller !== ws
      ) {
        session.controller.close(
          1000,
          'Replaced by a newer connection'
        );
      }

      session.controller = ws;

      console.log(
        '[CONNECT] controller',
        req.socket.remoteAddress
      );

      send(
        ws,
        {
          type: 'hello',
          role,
          pairCode: PAIR_CODE
        }
      );

      sendDeviceList(session);
    }

    /*
     * Agent:
     * كل هاتف له deviceId مستقل.
     */
    if (role === 'agent') {
      if (!deviceId) {
        sendError(
          ws,
          'Agent deviceId is missing'
        );

        ws.close(
          1008,
          'Missing deviceId'
        );

        return;
      }

      const oldSocket =
        session.agents.get(deviceId);

      if (
        isOpen(oldSocket) &&
        oldSocket !== ws
      ) {
        oldSocket.close(
          1000,
          'Replaced by a newer connection'
        );
      }

      session.agents.set(
        deviceId,
        ws
      );

      console.log(
        `[CONNECT] agent deviceId=${deviceId} remote=${req.socket.remoteAddress}`
      );

      send(
        ws,
        {
          type: 'hello',
          role,
          pairCode: PAIR_CODE,
          deviceId
        }
      );

      sendDeviceList(session);

      if (isOpen(session.controller)) {
        send(
          session.controller,
          {
            type: 'peer',
            online: true,
            deviceId
          }
        );
      }
    }

    ws.on(
      'message',
      (raw, isBinary) => {
        /*
         * Agent -> Controller
         */
        if (role === 'agent') {
          const controller =
            session.controller;

          if (!isOpen(controller)) {
            console.log(
              `[agent:${deviceId}] NO CONTROLLER CONNECTED`
            );

            return;
          }

          if (isBinary) {
            /*
             * نمرر الـ binary للـ controller.
             * activeDeviceId يمنع اختلاط بث هاتف آخر
             * أثناء جلسة التحكم الحالية.
             */
            if (
              !session.activeDeviceId ||
              session.activeDeviceId === deviceId
            ) {
              console.log(
                `[agent:${deviceId}] BINARY FRAME: ${raw.length} bytes`
              );

              sendRaw(
                controller,
                raw
              );
            }

            return;
          }

          const text =
            raw.toString();

          console.log(
            `[agent:${deviceId}] MESSAGE:`,
            text
          );

          let msg;

          try {
            msg = JSON.parse(text);
          } catch {
            console.log(
              `[agent:${deviceId}] INVALID JSON`
            );

            return;
          }

          /*
           * نضيف deviceId للرسائل الراجعة
           * حتى يعرف الـ Controller أي هاتف رد.
           */
          if (
            msg &&
            typeof msg === 'object' &&
            !msg.deviceId
          ) {
            msg.deviceId = deviceId;
          }

          console.log(
            `[agent:${deviceId}] FORWARDING TO CONTROLLER:`,
            msg
          );

          send(
            controller,
            msg
          );

          return;
        }

        /*
         * Controller -> Agent
         */
        if (role === 'controller') {
          const text =
            raw.toString();

          console.log(
            '[controller] MESSAGE:',
            text
          );

          let msg;

          try {
            msg = JSON.parse(text);
          } catch {
            console.log(
              '[controller] INVALID JSON'
            );

            sendError(
              ws,
              'Invalid JSON'
            );

            return;
          }

          const type =
            msg?.type || '';

          /*
           * get_devices لا يذهب للهاتف.
           * السيرفر نفسه يرجع القائمة.
           */
          if (type === 'get_devices') {
            console.log(
              '[controller] GET DEVICES'
            );

            sendDeviceList(
              session
            );

            return;
          }

          const targetDeviceId =
            msg?.deviceId?.toString().trim() || '';

          if (!targetDeviceId) {
            sendError(
              ws,
              'deviceId is required'
            );

            console.log(
              '[controller] Missing deviceId for message:',
              msg
            );

            return;
          }

          const target =
            session.agents.get(
              targetDeviceId
            );

          if (!isOpen(target)) {
            sendError(
              ws,
              `Device ${targetDeviceId} is not connected`
            );

            console.log(
              `[controller] NO TARGET CONNECTED deviceId=${targetDeviceId}`
            );

            return;
          }

          /*
           * نحدد آخر جهاز أرسل له الـ Controller أمر.
           * ده مهم للـ binary الخاص بالشاشة/الكاميرا.
           */
          session.activeDeviceId =
            targetDeviceId;

          console.log(
            `[controller] FORWARDING type=${type} to deviceId=${targetDeviceId}`
          );

          send(
            target,
            msg
          );
        }
      }
    );

    ws.on(
      'close',
      (closeCode, reason) => {
        const reasonText =
          reason?.toString() || '';

        console.log(
          `[CLOSE] role=${role} deviceId=${deviceId || '-'} code=${closeCode} reason=${reasonText}`
        );

        /*
         * Controller closed.
         */
        if (
          role === 'controller' &&
          session.controller === ws
        ) {
          session.controller = null;
          session.activeDeviceId = null;

          for (
            const [id] of session.agents
          ) {
            send(
              session.agents.get(id),
              {
                type: 'peer',
                online: false
              }
            );
          }

          return;
        }

        /*
         * Agent closed.
         */
        if (
          role === 'agent' &&
          session.agents.get(deviceId) === ws
        ) {
          session.agents.delete(
            deviceId
          );

          if (
            session.activeDeviceId === deviceId
          ) {
            session.activeDeviceId = null;
          }

          console.log(
            `[DEVICE OFFLINE] ${deviceId}`
          );

          sendDeviceList(
            session
          );

          if (isOpen(session.controller)) {
            send(
              session.controller,
              {
                type: 'peer',
                online: false,
                deviceId
              }
            );
          }
        }
      }
    );

    ws.on(
      'error',
      (error) => {
        console.error(
          `[WS ERROR] role=${role} deviceId=${deviceId || '-'}`,
          error
        );
      }
    );
  }
);

server.listen(
  PORT,
  () => {
    console.log(
      `Remote device POC server listening on port ${PORT}`
    );

    console.log(
      `PAIR_CODE=${PAIR_CODE}`
    );

    console.log(
      `Health check: http://localhost:${PORT}/health`
    );
  }
);
