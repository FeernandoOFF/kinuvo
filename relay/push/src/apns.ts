import type { Env, Outcome, Push } from "./env"
import { base64url, signJwt } from "./jwt"

const JWT_LIFETIME_MS = 50 * 60 * 1000
const DEAD_REASONS = new Set(["BadDeviceToken", "DeviceTokenNotForTopic", "Unregistered"])
const STALE_JWT_REASONS = new Set(["ExpiredProviderToken", "InvalidProviderToken"])

let cached: { id: string; jwt: Promise<string>; issuedAt: number } | undefined

export const apnsConfigured = (env: Env) => !!(env.APNS_KEY && env.APNS_KEY_ID && env.APNS_TEAM_ID && env.APNS_TOPIC)

function providerToken(env: Env): Promise<string> {
  const id = `${env.APNS_TEAM_ID}/${env.APNS_KEY_ID}`
  const now = Date.now()
  if (!cached || cached.id !== id || now - cached.issuedAt > JWT_LIFETIME_MS) {
    const jwt = signJwt("ES256", env.APNS_KEY!, { kid: env.APNS_KEY_ID }, { iss: env.APNS_TEAM_ID, iat: Math.floor(now / 1000) })
    cached = { id, jwt, issuedAt: now }
    jwt.catch(() => { if (cached?.jwt === jwt) cached = undefined })
  }
  return cached.jwt
}

export async function sendApns(env: Env, sandbox: boolean, push: Push): Promise<Outcome> {
  const host = sandbox ? "api.sandbox.push.apple.com" : "api.push.apple.com"
  const response = await fetch(`https://${host}/3/device/${push.token}`, {
    method: "POST",
    headers: {
      authorization: `bearer ${await providerToken(env)}`,
      "apns-topic": env.APNS_TOPIC!,
      "apns-push-type": "alert",
      "apns-priority": push.urgent ? "10" : "5",
      "apns-expiration": String(Math.floor(Date.now() / 1000) + push.ttl),
      "content-type": "application/json",
    },
    body: JSON.stringify({
      aps: { alert: { title: "Kinuvo", body: "New activity" }, "mutable-content": 1, sound: "default" },
      k: base64url(push.body),
    }),
  })
  if (response.ok) return "sent"
  const reason = await response.json<{ reason?: string }>().then((it) => it.reason ?? "", () => "")
  console.warn(`APNs ${response.status} ${reason}`)
  if (response.status === 410 || (response.status === 400 && DEAD_REASONS.has(reason))) return "gone"
  if (response.status === 429) return "throttled"
  if (response.status === 403 && STALE_JWT_REASONS.has(reason)) cached = undefined
  return "failed"
}
