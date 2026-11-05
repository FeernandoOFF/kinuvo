import type { Env, Outcome, Push } from "./env"
import { base64url, signJwt } from "./jwt"

const SCOPE = "https://www.googleapis.com/auth/firebase.messaging"
const TOKEN_URL = "https://oauth2.googleapis.com/token"
const EXPIRY_MARGIN_MS = 5 * 60 * 1000

interface ServiceAccount { client_email: string; private_key: string }

let cached: { email: string; token: Promise<string>; expiresAt: number } | undefined

export const fcmConfigured = (env: Env) => !!(env.FCM_PROJECT_ID && env.FCM_SERVICE_ACCOUNT)

async function exchange(account: ServiceAccount, now: number): Promise<{ token: string; expiresAt: number }> {
  const iat = Math.floor(now / 1000)
  const assertion = await signJwt("RS256", account.private_key, {}, { iss: account.client_email, scope: SCOPE, aud: TOKEN_URL, iat, exp: iat + 3600 })
  const response = await fetch(TOKEN_URL, {
    method: "POST",
    headers: { "content-type": "application/x-www-form-urlencoded" },
    body: new URLSearchParams({ grant_type: "urn:ietf:params:oauth:grant-type:jwt-bearer", assertion }),
  })
  if (!response.ok) throw new Error(`OAuth ${response.status}`)
  const { access_token, expires_in } = await response.json<{ access_token: string; expires_in: number }>()
  return { token: access_token, expiresAt: now + expires_in * 1000 - EXPIRY_MARGIN_MS }
}

function serviceAccount(json: string): ServiceAccount {
  try {
    return JSON.parse(json)
  } catch {
    throw new Error("FCM_SERVICE_ACCOUNT is not valid JSON")
  }
}

function accessToken(env: Env): Promise<string> {
  const account = serviceAccount(env.FCM_SERVICE_ACCOUNT!)
  const now = Date.now()
  if (!cached || cached.email !== account.client_email || now >= cached.expiresAt) {
    const entry = { email: account.client_email, token: Promise.resolve(""), expiresAt: Infinity }
    entry.token = exchange(account, now).then(
      ({ token, expiresAt }) => { entry.expiresAt = expiresAt; return token },
      (e) => { if (cached === entry) cached = undefined; throw e },
    )
    cached = entry
  }
  return cached.token
}

interface FcmError { error?: { status?: string; message?: string; details?: { errorCode?: string }[] } }

export async function sendFcm(env: Env, push: Push): Promise<Outcome> {
  const response = await fetch(`https://fcm.googleapis.com/v1/projects/${env.FCM_PROJECT_ID}/messages:send`, {
    method: "POST",
    headers: { authorization: `Bearer ${await accessToken(env)}`, "content-type": "application/json" },
    body: JSON.stringify({
      message: {
        token: push.token,
        data: { k: base64url(push.body) },
        android: { priority: push.urgent ? "HIGH" : "NORMAL", ttl: `${push.ttl}s` },
      },
    }),
  })
  if (response.ok) return "sent"
  const { error } = await response.json<FcmError>().catch((): FcmError => ({}))
  const codes = [error?.status, ...(error?.details ?? []).map((it) => it.errorCode)]
  console.warn(`FCM ${response.status} ${codes.filter(Boolean).join(" ")}`)
  if (response.status === 404 || codes.includes("UNREGISTERED")) return "gone"
  if (codes.includes("INVALID_ARGUMENT") && /registration token/i.test(error?.message ?? "")) return "gone"
  if (response.status === 429 || codes.includes("QUOTA_EXCEEDED")) return "throttled"
  if (response.status === 401) cached = undefined
  return "failed"
}
