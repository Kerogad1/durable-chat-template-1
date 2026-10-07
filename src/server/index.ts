import {
  type Connection,
  Server,
  type WSMessage,
  routePartykitRequest,
} from "partyserver";

type RemoteRole = "agent" | "controller";

type RemoteConnectionState = {
  role: RemoteRole;
  deviceId: string;
  deviceName: string;
};

function sendJson(
  connection: Connection,
  message: Record<string, unknown>,
) {
  try {
    connection.send(JSON.stringify(message));
  } catch (error) {
    console.error("[REMOTE SEND ERROR]", error);
  }
}

export class Chat extends Server<Env> {
  static options = {
    hibernate: true,
  };

  private getState(
    connection: Connection,
  ): RemoteConnectionState | null {
    const state = connection.state as
      | Partial<RemoteConnectionState>
      | null
      | undefined;

    if (
      !state ||
      (state.role !== "agent" &&
        state.role !== "controller")
    ) {
      return null;
    }

    return {
      role: state.role,
      deviceId: state.deviceId || "",
      deviceName:
        state.deviceName || "Android",
    };
  }

  private getController(): Connection | null {
    for (const connection of this.getConnections()) {
      const state = this.getState(connection);

      if (state?.role === "controller") {
        return connection;
      }
    }

    return null;
  }

  private getAgentConnections(): Connection[] {
    return [...this.getConnections()].filter(
      (connection) =>
        this.getState(connection)?.role ===
        "agent",
    );
  }

  private getDeviceList() {
    return this.getAgentConnections().map(
      (connection) => {
        const state = this.getState(connection);

        return {
          deviceId: state?.deviceId || connection.id,
          deviceName:
            state?.deviceName ||
            `Android ${(
              state?.deviceId ||
              connection.id
            ).slice(-4)}`,
          online: true,
        };
      },
    );
  }

  private sendDeviceList() {
    const controller = this.getController();

    if (!controller) {
      return;
    }

    const devices = this.getDeviceList();

    console.log(
      "[REMOTE] DEVICE LIST",
      devices,
    );

    sendJson(controller, {
      type: "device_list",
      devices,
    });
  }

  private sendError(
    connection: Connection,
    message: string,
  ) {
    sendJson(connection, {
      type: "error",
      message,
    });
  }

  private findAgent(
    deviceId: string,
  ): Connection | null {
    for (const connection of this.getAgentConnections()) {
      const state = this.getState(connection);

      if (state?.deviceId === deviceId) {
        return connection;
      }
    }

    return null;
  }

  onConnect(
    connection: Connection,
    context: {
      request: Request;
    },
  ) {
    const url = new URL(
      connection.uri ||
        context.request.url,
    );

    const role =
      url.searchParams.get("role") as
        | RemoteRole
        | null;

    const code =
      url.searchParams.get("code")?.trim() ||
      "";

    const deviceId =
      url.searchParams.get("deviceId")?.trim() ||
      "";

    const deviceName =
      url.searchParams.get("deviceName")?.trim() ||
      "";

    if (
      role !== "agent" &&
      role !== "controller"
    ) {
      console.error(
        "[REMOTE] Invalid role:",
        role,
      );

      connection.close(
        1008,
        "Invalid role",
      );

      return;
    }

    if (!code) {
      console.error(
        "[REMOTE] Missing pair code",
      );

      connection.close(
        1008,
        "Missing pair code",
      );

      return;
    }

    if (
      role === "agent" &&
      !deviceId
    ) {
      console.error(
        "[REMOTE] Agent missing deviceId",
      );

      connection.close(
        1008,
        "Missing deviceId",
      );

      return;
    }

    /*
     * كل اتصال يحمل بياناته داخل state.
     * state يستمر مع PartyServer/hibernation لنفس اتصال WebSocket.
     */
    connection.setState({
      role,
      deviceId:
        role === "agent"
          ? deviceId
          : "",
      deviceName:
        role === "agent"
          ? deviceName ||
            `Android ${deviceId.slice(-4)}`
          : "Remote Controller",
    } satisfies RemoteConnectionState);

    console.log(
      "[REMOTE] CONNECT",
      {
        role,
        deviceId,
        code,
        room: this.name,
      },
    );

    /*
     * نسمح بـ Controller واحد لكل غرفة.
     */
    if (role === "controller") {
      const oldController =
        this.getController();

      if (
        oldController &&
        oldController.id !== connection.id
      ) {
        oldController.close(
          1000,
          "Replaced by newer controller",
        );
      }

      sendJson(connection, {
        type: "hello",
        role: "controller",
        pairCode: code,
      });

      this.sendDeviceList();

      return;
    }

    /*
     * نفس الهاتف لا يحتفظ بأكثر من اتصال Agent واحد.
     */
    const oldAgent =
      this.findAgent(deviceId);

    if (
      oldAgent &&
      oldAgent.id !== connection.id
    ) {
      oldAgent.close(
        1000,
        "Replaced by newer connection",
      );
    }

    sendJson(connection, {
      type: "hello",
      role: "agent",
      pairCode: code,
      deviceId,
    });

    this.sendDeviceList();

    const controller =
      this.getController();

    if (controller) {
      sendJson(controller, {
        type: "peer",
        online: true,
        deviceId,
      });
    }
  }

  onMessage(
    connection: Connection,
    message: WSMessage,
  ) {
    const state =
      this.getState(connection);

    if (!state) {
      this.sendError(
        connection,
        "Connection state is missing",
      );
      return;
    }

    /*
     * Agent -> Controller
     */
    if (state.role === "agent") {
      const controller =
        this.getController();

      if (!controller) {
        console.log(
          "[REMOTE] Agent message dropped: no controller",
        );
        return;
      }

      /*
       * بث الشاشة/الكاميرا يصل كـ binary.
       * نمرره فقط للـ Controller الحالي.
       */
      if (
        typeof message !== "string"
      ) {
        try {
          controller.send(
            message as ArrayBuffer,
          );
        } catch (error) {
          console.error(
            "[REMOTE] Binary forward error:",
            error,
          );
        }

        return;
      }

      let msg: Record<string, unknown>;

      try {
        msg = JSON.parse(message) as Record<
          string,
          unknown
        >;
      } catch {
        console.error(
          "[REMOTE] Invalid JSON from agent:",
          message,
        );
        return;
      }

      if (!msg.deviceId) {
        msg.deviceId = state.deviceId;
      }

      console.log(
        "[REMOTE] AGENT -> CONTROLLER",
        {
          type: msg.type,
          deviceId:
            msg.deviceId,
        },
      );

      sendJson(
        controller,
        msg,
      );

      return;
    }

    /*
     * Controller -> Agent
     */
    if (typeof message !== "string") {
      this.sendError(
        connection,
        "Binary messages are not accepted from controller",
      );
      return;
    }

    let msg: Record<string, unknown>;

    try {
      msg = JSON.parse(message) as Record<
        string,
        unknown
      >;
    } catch {
      this.sendError(
        connection,
        "Invalid JSON",
      );
      return;
    }

    const type =
      typeof msg.type === "string"
        ? msg.type
        : "";

    console.log(
      "[REMOTE] CONTROLLER MESSAGE",
      {
        type,
        deviceId:
          msg.deviceId || "",
      },
    );

    /*
     * طلب الأجهزة يعالجه Cloudflare نفسه
     * ولا يصل إلى أي هاتف.
     */
    if (type === "get_devices") {
      this.sendDeviceList();
      return;
    }

    const targetDeviceId =
      typeof msg.deviceId === "string"
        ? msg.deviceId.trim()
        : "";

    if (!targetDeviceId) {
      this.sendError(
        connection,
        "deviceId is required",
      );
      return;
    }

    const target =
      this.findAgent(targetDeviceId);

    if (!target) {
      this.sendError(
        connection,
        `Device ${targetDeviceId} is not connected`,
      );

      console.log(
        "[REMOTE] TARGET NOT FOUND",
        targetDeviceId,
      );

      return;
    }

    console.log(
      "[REMOTE] CONTROLLER -> AGENT",
      {
        type,
        deviceId:
          targetDeviceId,
      },
    );

    sendJson(
      target,
      msg,
    );
  }

  onClose(
    connection: Connection,
    code: number,
    reason: string,
    _wasClean: boolean,
  ) {
    const state =
      this.getState(connection);

    console.log(
      "[REMOTE] CLOSE",
      {
        role: state?.role || "unknown",
        deviceId:
          state?.deviceId || "",
        code,
        reason,
      },
    );

    /*
     * بعد إغلاق الاتصال، getConnections()
     * لن يعيد هذا الاتصال، لذلك إرسال القائمة
     * هنا يكفي لتحديث Controller.
     */
    this.sendDeviceList();

    if (
      state?.role === "agent"
    ) {
      const controller =
        this.getController();

      if (controller) {
        sendJson(controller, {
          type: "peer",
          online: false,
          deviceId:
            state.deviceId,
        });
      }
    }
  }
}

export default {
  async fetch(
    request: Request,
    env: Env,
  ) {
    const url = new URL(request.url);

    /*
     * Health check بسيط لـ Cloudflare.
     * لا يعرض كود الاقتران.
     */
    if (
      url.pathname === "/health" &&
      request.method === "GET"
    ) {
      return new Response(
        JSON.stringify({
          ok: true,
          service: "remote-device-poc-cloudflare",
        }),
        {
          status: 200,
          headers: {
            "content-type":
              "application/json",
          },
        },
      );
    }

    /*
     * تطبيق Android الحالي يرسل WebSocket
     * إلى نفس serverUrl مباشرة، ثم يضيف:
     *   ?role=...&code=...
     *
     * لذلك ندعم root WebSocket أيضًا.
     *
     * كل pair code يصبح غرفة Durable Object مستقلة.
     */
    if (
      url.pathname === "/" &&
      request.headers
        .get("Upgrade")
        ?.toLowerCase() ===
        "websocket"
    ) {
      const code =
        url.searchParams
          .get("code")
          ?.trim()
          .toUpperCase() || "";

      if (!code) {
        return new Response(
          "Missing pair code",
          { status: 400 },
        );
      }

      const id =
        env.Chat.idFromName(code);

      const stub =
        env.Chat.get(id);

      /*
       * PartyServer يحتاج room name عند الاستدعاء المباشر
       * لأنه لم يمر عبر routePartykitRequest.
       */
      const forwarded =
        new Request(request);

      forwarded.headers.set(
        "x-partykit-room",
        code,
      );

      return stub.fetch(
        forwarded,
      );
    }

    /*
     * المسار القياسي لـ PartyServer:
     * /parties/:party/:room
     */
    return (
      (await routePartykitRequest(
        request,
        { ...env },
      )) ||
      env.ASSETS.fetch(request)
    );
  },
} satisfies ExportedHandler<Env>;
