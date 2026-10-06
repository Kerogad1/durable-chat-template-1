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
  online: boolean;
};

function textValue(value: unknown): string | undefined {
  if (typeof value !== "string") {
    return undefined;
  }

  const result = value.trim();
  return result.length > 0 ? result : undefined;
}

function parseJson(value: string): Record<string, unknown> | null {
  try {
    const parsed: unknown = JSON.parse(value);

    if (
      typeof parsed === "object" &&
      parsed !== null &&
      !Array.isArray(parsed)
    ) {
      return parsed as Record<string, unknown>;
    }

    return null;
  } catch {
    return null;
  }
}

function isTextMessage(message: WSMessage): message is string {
  return typeof message === "string";
}

export class Chat extends Server<Env> {
  static options = {
    hibernate: true,
  };

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

  private getConnectionsList(): Connection<RemoteState>[] {
    return Array.from(
      this.getConnections<RemoteState>(),
    );
  }

  private getAgentConnections(): Connection<RemoteState>[] {
    return this.getConnectionsList().filter(
      (connection) => connection.state?.role === "agent",
    );
  }

  private getControllerConnections(): Connection<RemoteState>[] {
    return this.getConnectionsList().filter(
      (connection) => connection.state?.role === "controller",
    );
  }

  private findAgent(
    deviceId: string,
  ): Connection<RemoteState> | undefined {
    return this.getAgentConnections().find(
      (connection) =>
        connection.state?.deviceId === deviceId,
    );
  }

  private getSelectedControllers(
    deviceId: string,
  ): Connection<RemoteState>[] {
    return this.getControllerConnections().filter(
      (connection) =>
        connection.state?.targetDeviceId === deviceId,
    );
  }

  private getOnlineDeviceIds(): Set<string> {
    const result = new Set<string>();

    for (const connection of this.getAgentConnections()) {
      const deviceId = connection.state?.deviceId;

      if (deviceId) {
        result.add(deviceId);
      }
    }

    return result;
  }

  private getStoredDevices(): DeviceRecord[] {
    const rows = this.ctx.storage.sql
      .exec(
        `
          SELECT
            device_id,
            device_name,
            model,
            last_seen
          FROM devices
          ORDER BY device_name COLLATE NOCASE
        `,
      )
      .toArray() as Array<{
      device_id: string;
      device_name: string;
      model: string;
      last_seen: number;
    }>;

    const online = this.getOnlineDeviceIds();

    return rows.map((row) => ({
      deviceId: row.device_id,
      deviceName: row.device_name,
      model: row.model,
      lastSeen: row.last_seen,
      online: online.has(row.device_id),
    }));
  }

  private saveDevice(
    deviceId: string,
    deviceName: string,
    model: string,
  ) {
    this.ctx.storage.sql.exec(
      `
        INSERT INTO devices (
          device_id,
          device_name,
          model,
          last_seen
        )
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
      Date.now(),
    );
  }

  private sendToControllers(
    deviceId: string,
    message: string | ArrayBuffer | ArrayBufferView,
  ) {
    const controllers = this.getSelectedControllers(
      deviceId,
    );

    for (const controller of controllers) {
      try {
        controller.send(message);
      } catch (error) {
        console.error(
          "Failed to send to controller:",
          error,
        );
      }
    }
  }

  private sendDeviceListToAllControllers() {
    const message = JSON.stringify({
      type: "device_list",
      devices: this.getStoredDevices(),
    });

    for (const controller of this.getControllerConnections()) {
      try {
        controller.send(message);
      } catch (error) {
        console.error(
          "Failed to send device list:",
          error,
        );
      }
    }
  }

  async onConnect(
    connection: Connection<RemoteState>,
    context: { request: Request },
  ) {
    const url = new URL(context.request.url);

    const role = textValue(
      url.searchParams.get("role"),
    );

    if (
      role !== "agent" &&
      role !== "controller"
    ) {
      connection.close(
        1008,
        "Invalid role",
      );
      return;
    }

    if (role === "agent") {
      const deviceId =
        textValue(
          url.searchParams.get("deviceId"),
        ) ??
        `device-${connection.id}`;

      const deviceName =
        textValue(
          url.searchParams.get("deviceName"),
        ) ??
        deviceId;

      const model =
        textValue(
          url.searchParams.get("model"),
        ) ??
        "";

      connection.setState({
        role: "agent",
        deviceId,
        deviceName,
      });

      this.saveDevice(
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

      this.sendDeviceListToAllControllers();

      console.log(
        `Agent connected: ${deviceId}`,
      );

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
        devices: this.getStoredDevices(),
      }),
    );

    console.log(
      `Controller connected: ${connection.id}`,
    );
  }

  async onMessage(
    connection: Connection<RemoteState>,
    message: WSMessage,
  ) {
    const state = connection.state;

    if (!state) {
      connection.close(
        1008,
        "Missing connection state",
      );
      return;
    }

    /*
     * البيانات الثنائية:
     * صور / فيديو / صوت / ملفات
     */
    if (!isTextMessage(message)) {
      if (state.role === "controller") {
        const deviceId =
          state.targetDeviceId;

        if (!deviceId) {
          connection.send(
            JSON.stringify({
              type: "error",
              message:
                "No device selected",
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
              message:
                "Target device is offline",
              deviceId,
            }),
          );

          return;
        }

        try {
          agent.send(message);
        } catch (error) {
          console.error(
            "Binary forwarding error:",
            error,
          );
        }

        return;
      }

      if (
        state.role === "agent" &&
        state.deviceId
      ) {
        this.sendToControllers(
          state.deviceId,
          message,
        );
      }

      return;
    }

    const parsed =
      parseJson(message);

    if (!parsed) {
      connection.send(
        JSON.stringify({
          type: "error",
          message:
            "Invalid JSON message",
        }),
      );

      return;
    }

    const messageType =
      textValue(parsed.type);

    /*
     * أوامر جهاز التحكم
     */
    if (state.role === "controller") {
      /*
       * طلب قائمة الأجهزة
       */
      if (
        messageType === "get_devices"
      ) {
        connection.send(
          JSON.stringify({
            type: "device_list",
            devices:
              this.getStoredDevices(),
          }),
        );

        return;
      }

      /*
       * اختيار هاتف معين
       */
      if (
        messageType === "select_device"
      ) {
        const deviceId =
          textValue(parsed.deviceId);

        if (!deviceId) {
          connection.send(
            JSON.stringify({
              type: "error",
              message:
                "deviceId is required",
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
              message:
                "Device is offline",
              deviceId,
            }),
          );

          return;
        }

        connection.setState({
          role: "controller",
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

      /*
       * بقية أوامر التحكم
       */
      let deviceId =
        textValue(
          parsed.deviceId,
        ) ??
        state.targetDeviceId;

      /*
       * لو يوجد هاتف واحد فقط
       * نختاره تلقائيًا.
       */
      if (!deviceId) {
        const agents =
          this.getAgentConnections();

        if (agents.length === 1) {
          deviceId =
            agents[0].state
              ?.deviceId;
        }
      }

      if (!deviceId) {
        connection.send(
          JSON.stringify({
            type: "error",
            message:
              "Select a device first",
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
            message:
              "Target device is offline",
            deviceId,
          }),
        );

        return;
      }

      /*
       * نثبت الهاتف المستهدف على جلسة
       * جهاز التحكم.
       */
      if (
        state.targetDeviceId !==
        deviceId
      ) {
        connection.setState({
          ...state,
          targetDeviceId: deviceId,
        });
      }

      /*
       * نعيد إرسال الأمر للهاتف
       */
      const forwarded = {
        ...parsed,
        deviceId,
      };

      try {
        agent.send(
          JSON.stringify(forwarded),
        );
      } catch (error) {
        console.error(
          "Command forwarding error:",
          error,
        );

        connection.send(
          JSON.stringify({
            type: "error",
            message:
              "Failed to send command",
            deviceId,
          }),
        );
      }

      return;
    }

    /*
     * رسائل الهاتف إلى جهاز التحكم
     */
    if (
      state.role === "agent" &&
      state.deviceId
    ) {
      /*
       * تحديث حالة الجهاز
       */
      if (
        messageType ===
        "device_info"
      ) {
        const deviceName =
          textValue(
            parsed.deviceName,
          ) ??
          state.deviceName ??
          state.deviceId;

        const model =
          textValue(
            parsed.model,
          ) ??
          "";

        connection.setState({
          ...state,
          deviceName,
        });

        this.saveDevice(
          state.deviceId,
          deviceName,
          model,
        );

        this.sendDeviceListToAllControllers();
      }

      /*
       * إضافة معرف الجهاز إلى الرسالة
       */
      const response = JSON.stringify({
        ...parsed,
        deviceId:
          state.deviceId,
      });

      /*
       * إرسال الرسالة فقط إلى أجهزة
       * التحكم التي اختارت هذا الهاتف.
       */
      this.sendToControllers(
        state.deviceId,
        response,
      );

      return;
    }
  }

  async onClose(
    connection: Connection<RemoteState>,
    _code: number,
    _reason: string,
    _wasClean: boolean,
  ) {
    const state =
      connection.state;

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

      this.sendDeviceListToAllControllers();

      console.log(
        `Agent disconnected: ${state.deviceId}`,
      );
    }
  }
}

export default {
  async fetch(
    request: Request,
    env: Env,
  ): Promise<Response> {
    const url =
      new URL(request.url);

    /*
     * رمز الاقتران
     */
    const code =
      textValue(
        url.searchParams.get("code"),
      );

    /*
     * نوع الاتصال
     */
    const role =
      textValue(
        url.searchParams.get("role"),
      );

    /*
     * لا نضع الرمز داخل الكود.
     * سنضعه لاحقًا في متغير سري
     * داخل كلاودفلير.
     */
    const configuredCode =
      textValue(
        (env as Env & {
          PAIR_CODE?: string;
        }).PAIR_CODE,
      );

    if (!configuredCode) {
      return new Response(
        "PAIR_CODE is not configured",
        {
          status: 500,
        },
      );
    }

    if (
      !code ||
      code !== configuredCode
    ) {
      return new Response(
        "Invalid pair code",
        {
          status: 401,
        },
      );
    }

    if (
      role !== "agent" &&
      role !== "controller"
    ) {
      return new Response(
        "Invalid role",
        {
          status: 400,
        },
      );
    }

    /*
     * فحص عادي
     */
    if (
      request.headers
        .get("Upgrade")
        ?.toLowerCase() !==
      "websocket"
    ) {
      return Response.json({
        ok: true,
        service:
          "remote-device-cloud",
        websocket: true,
        role,
      });
    }

    /*
     * كل رمز اقتران له غرفة مستقلة.
     */
    const routedUrl =
      new URL(request.url);

    routedUrl.pathname =
      `/parties/chat/${encodeURIComponent(
        configuredCode,
      )}`;

    const routedRequest =
      new Request(
        routedUrl,
        request,
      );

    const response =
      await routePartykitRequest(
        routedRequest,
        env,
      );

    return (
      response ??
      new Response(
        "Not Found",
        {
          status: 404,
        },
      )
    );
  },
} satisfies ExportedHandler<Env>;
