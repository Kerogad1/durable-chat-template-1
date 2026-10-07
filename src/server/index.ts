import { DurableObject } from "cloudflare:workers";

type RemoteSession = {
  role: "agent" | "controller";
  deviceId?: string;
  deviceName?: string;
};

function value(v: string | null): string | undefined {
  const s = v?.trim();
  return s || undefined;
}

function isWebSocket(request: Request): boolean {
  return request.headers.get("Upgrade")?.toLowerCase() === "websocket";
}

function safeSend(ws: WebSocket, data: string | ArrayBuffer): void {
  try { ws.send(data); } catch {}
}

function safeJson(ws: WebSocket, data: Record<string, unknown>): void {
  safeSend(ws, JSON.stringify(data));
}

function parseText(text: string): Record<string, unknown> | null {
  try {
    const parsed = JSON.parse(text);
    if (!parsed || typeof parsed !== "object" || Array.isArray(parsed)) return null;
    return parsed as Record<string, unknown>;
  } catch {
    return null;
  }
}

export class RemoteRoom extends DurableObject {
  private sessions = new Map<WebSocket, RemoteSession>();

  constructor(ctx: DurableObjectState, env: Env) {
    super(ctx, env);
  }

  private allAgents() {
    return [...this.sessions.entries()].filter(([, state]) => state.role === "agent");
  }

  private allControllers() {
    return [...this.sessions.entries()].filter(([, state]) => state.role === "controller");
  }

  private findAgent(deviceId: string) {
    return this.allAgents().find(([, state]) => state.deviceId === deviceId)?.[0];
  }

  private deviceList() {
    return this.allAgents().map(([, state]) => ({
      deviceId: state.deviceId ?? "",
      deviceName: state.deviceName ?? "جهاز Android",
      online: true,
    }));
  }

  private sendDeviceList() {
    const payload = { type: "device_list", devices: this.deviceList() };
    for (const [ws] of this.allControllers()) safeJson(ws, payload);
  }

  private forwardToAgent(deviceId: string, data: string | ArrayBuffer) {
    const ws = this.findAgent(deviceId);
    if (!ws) return false;
    safeSend(ws, data);
    return true;
  }

  private forwardToControllers(deviceId: string, data: string | ArrayBuffer) {
    for (const [ws] of this.allControllers()) {
      safeSend(ws, data);
    }
  }

  async fetch(request: Request): Promise<Response> {
    if (request.headers.get("X-Remote-Debug") === "1") {
      return Response.json({
        ok: true,
        durableObject: "RemoteRoom",
        devices: this.deviceList(),
        sessions: this.sessions.size,
      });
    }

    if (!isWebSocket(request)) return new Response("WebSocket endpoint", { status: 426 });

    const url = new URL(request.url);
    const role = value(url.searchParams.get("role"));
    if (role !== "agent" && role !== "controller") {
      return new Response("Invalid role", { status: 400 });
    }

    const pairCode = value(url.searchParams.get("code"));
    if (!pairCode) return new Response("Missing pair code", { status: 400 });

    const pair = new WebSocketPair();
    const client = pair[0];
    const server = pair[1];
    server.accept();

    if (role === "agent") {
      const deviceId = value(url.searchParams.get("deviceId")) ?? ("device-" + crypto.randomUUID());
      const deviceName = value(url.searchParams.get("deviceName")) ?? "جهاز Android";

      for (const [oldWs, oldState] of this.allAgents()) {
        if (oldState.deviceId === deviceId) {
          try { oldWs.close(4000, "Replaced"); } catch {}
          this.sessions.delete(oldWs);
        }
      }

      this.sessions.set(server, { role: "agent", deviceId, deviceName });

      console.log("[REMOTE] AGENT CONNECT", deviceId);

      safeJson(server, {
        type: "connection_status",
        connected: true,
        role: "agent",
        deviceId,
      });

      safeJson(server, {
        type: "hello",
        role: "agent",
        deviceId,
        deviceName,
      });

      this.sendDeviceList();
    } else {
      for (const [oldWs] of this.allControllers()) {
        try { oldWs.close(4000, "Replaced"); } catch {}
        this.sessions.delete(oldWs);
      }

      this.sessions.set(server, { role: "controller" });

      console.log("[REMOTE] CONTROLLER CONNECT");

      safeJson(server, { type: "connection_status", connected: true, role: "controller" });
      safeJson(server, { type: "hello", role: "controller" });
      safeJson(server, { type: "device_list", devices: this.deviceList() });
    }

    server.addEventListener("message", (event) => {
      const state = this.sessions.get(server);
      if (!state) return;

      if (typeof event.data !== "string") {
        if (state.role === "agent" && state.deviceId) {
          this.forwardToControllers(state.deviceId, event.data);
        }
        return;
      }

      const data = parseText(event.data);
      if (!data) {
        safeJson(server, { type: "error", message: "Invalid JSON" });
        return;
      }

      const type = typeof data.type === "string" ? data.type : "";

      if (state.role === "agent") {
        const deviceId = state.deviceId ?? "";
        this.forwardToControllers(deviceId, JSON.stringify({ ...data, deviceId }));
        return;
      }

      if (type === "get_devices") {
        safeJson(server, { type: "device_list", devices: this.deviceList() });
        return;
      }

      const targetDeviceId = typeof data.deviceId === "string" ? data.deviceId.trim() : "";
      if (!targetDeviceId) {
        safeJson(server, { type: "error", message: "deviceId is required" });
        return;
      }

      if (!this.findAgent(targetDeviceId)) {
        safeJson(server, { type: "error", message: "الجهاز غير متصل", deviceId: targetDeviceId });
        return;
      }

      console.log("[REMOTE] COMMAND", type, targetDeviceId);
      this.forwardToAgent(targetDeviceId, JSON.stringify({ ...data, deviceId: targetDeviceId }));
    });

    const cleanup = () => {
      this.sessions.delete(server);
      console.log("[REMOTE] DISCONNECT");
      this.sendDeviceList();
    };

    server.addEventListener("close", cleanup);
    server.addEventListener("error", cleanup);

    return new Response(null, { status: 101, webSocket: client });
  }
}

export class Chat extends DurableObject {
  constructor(ctx: DurableObjectState, env: Env) {
    super(ctx, env);
  }

  async fetch(): Promise<Response> {
    return new Response("Legacy chat namespace", { status: 410 });
  }
}

export default {
  async fetch(request: Request, env: Env): Promise<Response> {
    const url = new URL(request.url);

    if (!isWebSocket(request) && (url.pathname === "/" || url.pathname === "/health")) {
      return Response.json({
        ok: true,
        service: "remote-device-cloud",
        websocket: true,
      });
    }

    const configuredPairCode = value(env.PAIR_CODE ?? null);
    if (!configuredPairCode) return new Response("PAIR_CODE is not configured", { status: 500 });

    const requestedCode = value(url.searchParams.get("code"));
    if (requestedCode !== configuredPairCode) return new Response("Invalid pair code", { status: 401 });

    const role = value(url.searchParams.get("role"));
    if (role !== "agent" && role !== "controller") {
      return new Response("Invalid role", { status: 400 });
    }

    const namespace = env.RemoteRoom;
    if (!namespace) return new Response("RemoteRoom binding is missing", { status: 500 });

    const id = namespace.idFromName(configuredPairCode);
    const stub = namespace.get(id);

    if (!isWebSocket(request)) {
      if (url.pathname === "/debug-do") {
        const debugRequest = new Request(request, {
          headers: new Headers({ "X-Remote-Debug": "1" }),
        });
        try {
          return await stub.fetch(debugRequest);
        } catch (error) {
          return new Response(
            "Durable Object binding error: " + (error instanceof Error ? error.message : String(error)),
            { status: 500 },
          );
        }
      }
      return new Response("WebSocket endpoint ready", { status: 426 });
    }

    try {
      return await stub.fetch(request);
    } catch (error) {
      console.error("Durable Object fetch failed", error);
      return new Response(
        "Durable Object error: " + (error instanceof Error ? error.message : String(error)),
        { status: 500 },
      );
    }
  },
} satisfies ExportedHandler<Env>;