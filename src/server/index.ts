import {
  type Connection,
  Server,
  type WSMessage,
  routePartykitRequest,
} from "partyserver";

type RemoteRole = "agent" | "controller";

type RemoteState = {
  role: RemoteRole;
  deviceId?: string;
  deviceName?: string;
  targetDeviceId?: string;
};

type DeviceRecord = {
  deviceId: string;
  deviceName: string;
  model: string;
  lastSeen: number;
};

type RemoteEnv = Env & {
  PAIR_CODE?: string;
};

function isTextMessage(message: WSMessage): message is string {
  return typeof message === "string";
}

function safeJsonParse(value: string): Record<string, unknown> | null {
  try {
    const parsed = JSON.parse(value);
    return parsed && typeof parsed === "object"
      ? (parsed as Record<string, unknown>)
      : null;
  } catch {
    return null;
  }
}

function stringValue(value: unknown): string | undefined {
  return typeof value === "string" && value.trim()
    ? value.trim()
    : undefined;
}

export class Chat extends Server<Env> {
  static options = { hibernate: true };

  onStart() {
    this.ctx.storage.sql.exec(`
      CREATE TABLE IF NOT EXISTS devices (
        device_id TEXT PRIMARY KEY,
        device_name TEXT NOT NULL,
        model TEXT NOT NULL DEFAULT '',
        last_seen INTEGER NOT NULL
      )
    `);
  }

  private getRemoteConnections(): Connection<RemoteState>[] {
    return Array.from(
      this.getConnections(),
    ) as Connection<RemoteState>[];
  }

  private getOnlineDevices(): Set<string> {
    const result = new Set<string>();

    for (const connection of this.getRemoteConnections()) {
      const state = connection.state;

      if (
        state?.role === "agent" &&
        state.deviceId
      ) {
        result.add(state.deviceId);
      }
    }

    return result;
  }

  private async getDeviceList() {
    const rows = this.ctx.storage.sql.exec(`
      SELECT device_id, device_name, model, last_seen
      FROM devices
      ORDER BY device_name COLLATE NOCASE
    `).toArray() as Array<{
      device_id: string;
      device_name: string;
      model: string;
      last_seen: number;
    }>;

    const onlineDevices = this.getOnlineDevices();

    return rows.map((row) => ({
      deviceId: row.device_id,
      deviceName: row.device_name,
      model: row.model,
      lastSeen: row.last_seen,
      online: onlineDevices.has(row.device_id),
    }));
  }

  private async sendDeviceList() {
    const devices = await this.getDeviceList();

    const message = JSON.stringify({
      type: "device_list",
      devices,
    });

    for (const connection of this.getRemoteConnections()) {
      if (connection.state?.role === "controller") {
        try {
          connection.send(message);
        } catch {
          // الاتصال قد يكون مغلقًا بالفعل.
        }
      }
    }
  }

  private async saveDevice(
    deviceId: string,
    deviceName: string,
    model: string,
  ) {
    const now = Date.now();

    this.ctx.storage.sql.exec(
      `
        INSERT INTO devices
          (device_id, device_name, model, last_seen)
        VALUES (?, ?, ?, ?)
        ON CONFLICT(device_id)
        DO UPDATE SET
          device_name = excluded.device_name,
          model = excluded.model,
          last_seen = excluded.last_seen
      `,
      deviceId,
      deviceName,
      model,
      now,
    );
  }

  private findAgent(deviceId: string): Connection<RemoteState> | null {
    for (const connection of this.getRemoteConnections()) {
      const state = connection.state;

      if (
        state?.role === "agent" &&
        state.deviceId === deviceId
      ) {
        return connection;
      }
    }

    return null;
  }

  private getControllersForDevice(
    deviceId: string,
  ): Connection<RemoteState>[] {
    return this.getRemoteConnections().filter((connection) => {
      const state = connection.state;

      return (
        state?.role === "controller" &&
        state.targetDeviceId === deviceId
      );
    });
  }

  async onConnect(
    connection: Connection<RemoteState>,
    context: { request: Request },
  ) {
    const url = new URL(context.request.url);
    const role = url.searchParams.get("role");

    if (role !== "agent" && role !== "controller") {
      connection.close(1008, "Invalid role");
      return;
    }

    if (role === "agent") {
      const deviceId =
        stringValue(url.searchParams.get("deviceId")) ||
        `device-${connection.id}`;

      const deviceName =
        stringValue(url.searchParams.get("deviceName")) ||
        deviceId;

      const model =
        stringValue(url.searchParams.get("model")) ||
        "";

      connection.setState({
        role: "agent",
        deviceId,
        deviceName,
      });

      await this.saveDevice(
        deviceId,
        deviceName,
        model,
      );

      connection.send(
        JSON.stringify({
          type: "connection_status",
          connected: true,
          role: "agent",
          deviceId,
        }),
      );

      await this.sendDeviceList();
      return;
    }

    connection.setState({
      role: "controller",
    });

    connection.send(
      JSON.stringify({
        type: "connection_status",
        connected: true,
        role: "controller",
      }),
    );

    connection.send(
      JSON.stringify({
        type: "device_list",
        devices: await this.getDeviceList(),
      }),
    );
  }

  async onMessage(
    connection: Connection<RemoteState>,
    message: WSMessage,
  ) {
    const state = connection.state;

    if (!state) {
      connection.close(1008, "Missing connection state");
      return;
    }

    // الصور / الفيديو / الصوت / الملفات الثنائية.
    if (!isTextMessage(message)) {
      if (state.role === "controller") {
        if (!state.targetDeviceId) {
          connection.send(
            JSON.stringify({
              type: "error",
              message: "No target device selected",
            }),
          );
          return;
        }

        const agent = this.findAgent(
          state.targetDeviceId,
        );

        if (!agent) {
          connection.send(
            JSON.stringify({
              type: "error",
              message: "Target device is offline",
              deviceId: state.targetDeviceId,
            }),
          );
          return;
        }

        try {
          agent.send(message);
        } catch {
          connection.send(
            JSON.stringify({
              type: "error",
              message: "Failed to forward binary data",
            }),
          );
        }

        return;
      }

      if (
        state.role === "agent" &&
        state.deviceId
      ) {
        const controllers =
          this.getControllersForDevice(
            state.deviceId,
          );

        for (const controller of controllers) {
          try {
            controller.send(message);
          } catch {
            // تجاهل الاتصالات التي أغلقت بالفعل.
          }
        }
      }

      return;
    }

    const parsed = safeJsonParse(message);

    if (!parsed) {
      connection.send(
        JSON.stringify({
          type: "error",
          message: "Invalid JSON message",
        }),
      );
      return;
    }

    // -----------------------------------------
    // أوامر جهاز التحكم
    // -----------------------------------------
    if (state.role === "controller") {
      const type = stringValue(parsed.type);

      if (type === "get_devices") {
        connection.send(
          JSON.stringify({
            type: "device_list",
            devices: await this.getDeviceList(),
          }),
        );
        return;
      }

      if (type === "select_device") {
        const deviceId =
          stringValue(parsed.deviceId);

        if (!deviceId) {
          connection.send(
            JSON.stringify({
              type: "error",
              message: "deviceId is required",
            }),
          );
          return;
        }

        const agent =
          this.findAgent(deviceId);

        if (!agent) {
          connection.send(
            JSON.stringify({
              type: "error",
              message: "Device is offline",
              deviceId,
            }),
          );
          return;
        }

        connection.setState({
          ...state,
          targetDeviceId: deviceId,
        });

        connection.send(
          JSON.stringify({
            type: "device_selected",
            deviceId,
          }),
        );

        return;
      }

      let targetDeviceId =
        stringValue(parsed.deviceId) ||
        state.targetDeviceId;

      // التوافق مع جهاز واحد:
      // لو فيه هاتف واحد فقط، نختاره تلقائيًا.
      if (!targetDeviceId) {
        const agents =
          this.getRemoteConnections().filter(
            (c) => c.state?.role === "agent",
          );

        if (agents.length === 1) {
          targetDeviceId =
            agents[0].state?.deviceId;
        }
      }

      if (!targetDeviceId) {
        connection.send(
          JSON.stringify({
            type: "error",
            message: "Select a device first",
          }),
        );
        return;
      }

      const agent =
        this.findAgent(targetDeviceId);

      if (!agent) {
        connection.send(
          JSON.stringify({
            type: "error",
            message: "Target device is offline",
            deviceId: targetDeviceId,
          }),
        );
        return;
      }

      const forwarded = {
        ...parsed,
        deviceId: targetDeviceId,
      };

      try {
        agent.send(JSON.stringify(forwarded));
      } catch {
        connection.send(
          JSON.stringify({
            type: "error",
            message: "Failed to forward command",
            deviceId: targetDeviceId,
          }),
        );
      }

      return;
    }

    // -----------------------------------------
    // رسائل الهاتف إلى Remote
    // -----------------------------------------
    if (
      state.role === "agent" &&
      state.deviceId
    ) {
      const updatedMessage = {
        ...parsed,
        deviceId: state.deviceId,
      };

      const output =
        JSON.stringify(updatedMessage);

      const controllers =
        this.getControllersForDevice(
          state.deviceId,
        );

      for (const controller of controllers) {
        try {
          controller.send(output);
        } catch {
          // تجاهل الاتصالات المغلقة.
        }
      }

      // تحديث معلومات الجهاز لو وصلت من الهاتف.
      if (
        stringValue(parsed.type) === "device_info"
      ) {
        const deviceName =
          stringValue(parsed.deviceName) ||
          state.deviceName ||
          state.deviceId;

        const model =
          stringValue(parsed.model) || "";

        connection.setState({
          ...state,
          deviceName,
        });

        await this.saveDevice(
          state.deviceId,
          deviceName,
          model,
        );

        await this.sendDeviceList();
      }

      return;
    }
  }

  async onClose(
    connection: Connection<RemoteState>,
    _code: number,
    _reason: string,
    _wasClean: boolean,
  ) {
    const state = connection.state;

    if (
      state?.role === "agent" &&
      state.deviceId
    ) {
      this.ctx.storage.sql.exec(
        `
          UPDATE devices
          SET last_seen = ?
          WHERE device_id = ?
        `,
        Date.now(),
        state.deviceId,
      );

      await this.sendDeviceList();
    }
  }
}

export default {
  async fetch(
    request: Request,
    env: Env,
  ): Promise<Response> {
    const url = new URL(request.url);
    const remoteEnv = env as RemoteEnv;

    const code =
      url.searchParams.get("code")?.trim() || "";

    const role =
      url.searchParams.get("role")?.trim() || "";

    // فحص رمز الاقتران.
    const configuredCode =
      remoteEnv.PAIR_CODE?.trim();

    if (!configuredCode) {
      return new Response(
        "PAIR_CODE is not configured",
        { status: 500 },
      );
    }

    if (!code || code !== configuredCode) {
      return new Response(
        "Invalid pair code",
        { status: 401 },
      );
    }

    if (
      role !== "agent" &&
      role !== "controller"
    ) {
      return new Response(
        "Invalid role",
        { status: 400 },
      );
    }

    // طلب عادي للفحص.
    if (
      request.headers.get("Upgrade")?.toLowerCase() !==
      "websocket"
    ) {
      return Response.json({
        ok: true,
        service: "remote-device-cloud",
        websocket: true,
      });
    }

    // نستخدم رمز الاقتران كاسم غرفة Durable Object.
    const routedUrl = new URL(request.url);

    routedUrl.pathname =
      `/parties/chat/${encodeURIComponent(code)}`;

    const routedRequest =
      new Request(routedUrl, request);

    return (
      (await routePartykitRequest(
        routedRequest,
        env,
      )) ||
      new Response("Not Found", {
        status: 404,
      })
    );
  },
} satisfies ExportedHandler<Env>;
