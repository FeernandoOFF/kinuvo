import { apnsConfigured, sendApns } from "./apns"
import type { Env, Outcome, Push } from "./env"
import { fcmConfigured, sendFcm } from "./fcm"

export type { Env } from "./env"

const MAX_BODY = 3072
const MAX_TTL = 28 * 24 * 60 * 60
const APNS_TOKEN = /^[0-9a-fA-F]{64,200}$/
const FCM_TOKEN = /^[A-Za-z0-9_:\-]{1,4096}$/

type Route =
  | { provider: "apns"; sandbox: boolean; token: string }
  | { provider: "fcm"; token: string }

const STATUS: Record<Outcome, number> = { sent: 201, gone: 410, throttled: 429, failed: 502 }

const status = (code: number, headers?: HeadersInit) => new Response(null, { status: code, headers })

function route(path: string): Route | "bad-token" | undefined {
  const parts = path.split("/").slice(1)
  if (parts[0] !== "v1") return undefined
  if (parts[1] === "fcm" && parts.length === 3) {
    return FCM_TOKEN.test(parts[2]) ? { provider: "fcm", token: parts[2] } : "bad-token"
  }
  if (parts[1] === "apns" && parts.length === 4 && (parts[2] === "sandbox" || parts[2] === "production")) {
    return APNS_TOKEN.test(parts[3]) ? { provider: "apns", sandbox: parts[2] === "sandbox", token: parts[3].toLowerCase() } : "bad-token"
  }
  return undefined
}

function ttl(header: string | null): number {
  const value = Number(header ?? 86400)
  return Number.isInteger(value) && value >= 0 ? Math.min(value, MAX_TTL) : 86400
}

async function sha256(text: string): Promise<string> {
  const digest = await crypto.subtle.digest("SHA-256", new TextEncoder().encode(text))
  return [...new Uint8Array(digest)].map((b) => b.toString(16).padStart(2, "0")).join("")
}

async function handle(request: Request, env: Env): Promise<Response> {
  const target = route(new URL(request.url).pathname)
  if (!target) return status(404)
  if (request.method !== "POST") return status(405, { allow: "POST" })
  if (target === "bad-token") return status(400)
  if (request.headers.get("content-encoding")?.toLowerCase() !== "aes128gcm") return status(400)
  if (Number(request.headers.get("content-length") ?? 0) > MAX_BODY) return status(413)
  const body = new Uint8Array(await request.arrayBuffer())
  if (body.length > MAX_BODY) return status(413)
  if (body.length === 0) return status(400)

  if (!(target.provider === "apns" ? apnsConfigured(env) : fcmConfigured(env))) return status(503)
  const { success } = await env.RATE_LIMITER.limit({ key: await sha256(`${target.provider}:${target.token}`) })
  if (!success) return status(429)

  const push: Push = { token: target.token, body, ttl: ttl(request.headers.get("ttl")), urgent: request.headers.get("urgency") === "high" }
  try {
    const outcome = target.provider === "apns" ? await sendApns(env, target.sandbox, push) : await sendFcm(env, push)
    return status(STATUS[outcome])
  } catch (e) {
    console.error(`${target.provider} send failed: ${e instanceof Error ? e.message : e}`)
    return status(502)
  }
}

export default { fetch: handle } satisfies ExportedHandler<Env>
