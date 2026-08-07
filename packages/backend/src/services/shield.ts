import { isAdminAuthorized } from "../middleware/admin";
import { isShieldEnabled } from "./site-config";

type ShieldedEndpoint = "current" | "timeline" | "health-data";

function noStoreJson(body: unknown): Response {
  return Response.json(body, {
    headers: { "Cache-Control": "no-store" },
  });
}

export function shouldShieldRequest(req: Request): boolean {
  return isShieldEnabled() && !isAdminAuthorized(req);
}

export function createShieldedResponse(endpoint: ShieldedEndpoint, url: URL): Response {
  const date = url.searchParams.get("date") || new Date().toISOString().slice(0, 10);

  if (endpoint === "timeline") {
    return noStoreJson({
      date,
      segments: [
        {
          app_name: "▓▒░ 猫猫保密中 ░▒▓",
          app_id: "████████",
          display_title: "░▒▓████▓▒░",
          started_at: `${date}T00:00:00.000Z`,
          ended_at: `${date}T00:01:00.000Z`,
          duration_minutes: 0,
          device_id: "██-██-██",
          device_name: "██████",
        },
      ],
      summary: { "██-██-██": { "▓▒░": 0 } },
      shielded: true,
    });
  }

  if (endpoint === "health-data") {
    return noStoreJson({ date, records: [], shielded: true });
  }

  return noStoreJson({
    devices: [
      {
        device_id: "██-██-██",
        device_name: "██████",
        platform: "▓▓▓▓",
        app_id: "████████",
        app_name: "▓▒░ 猫猫保密中 ░▒▓",
        display_title: "░▒▓████▓▒░",
        last_seen_at: "1970-01-01T00:00:00.000Z",
        is_online: 0,
        extra: {},
      },
    ],
    recent_activities: [],
    server_time: new Date().toISOString(),
    viewer_count: 0,
    shielded: true,
  });
}
