import { vi } from "vitest"
import type { Env } from "../src/index"

const pem = (der: ArrayBuffer) =>
  `-----BEGIN PRIVATE KEY-----\n${btoa(String.fromCharCode(...new Uint8Array(der))).match(/.{1,64}/g)!.join("\n")}\n-----END PRIVATE KEY-----\n`

export async function ecKey() {
  const pair = await crypto.subtle.generateKey({ name: "ECDSA", namedCurve: "P-256" }, true, ["sign", "verify"]) as CryptoKeyPair
  return { pem: pem(await crypto.subtle.exportKey("pkcs8", pair.privateKey) as ArrayBuffer), publicKey: pair.publicKey }
}

export async function rsaKey() {
  const pair = await crypto.subtle.generateKey(
    { name: "RSASSA-PKCS1-v1_5", modulusLength: 2048, publicExponent: new Uint8Array([1, 0, 1]), hash: "SHA-256" },
    true,
    ["sign", "verify"],
  ) as CryptoKeyPair
  return { pem: pem(await crypto.subtle.exportKey("pkcs8", pair.privateKey) as ArrayBuffer), publicKey: pair.publicKey }
}

export const fromBase64url = (text: string) =>
  Uint8Array.from(atob(text.replace(/-/g, "+").replace(/_/g, "/")), (c) => c.charCodeAt(0))

export function decodeJwt(jwt: string) {
  const [header, claims, signature] = jwt.split(".")
  const json = (part: string) => JSON.parse(new TextDecoder().decode(fromBase64url(part)))
  return { header: json(header), claims: json(claims), input: `${header}.${claims}`, signature: fromBase64url(signature) }
}

export function rateLimiter(limit = 30) {
  const counts = new Map<string, number>()
  const keys: string[] = []
  return {
    keys,
    limit: vi.fn(async ({ key }: { key: string }) => {
      keys.push(key)
      const count = (counts.get(key) ?? 0) + 1
      counts.set(key, count)
      return { success: count <= limit }
    }),
  }
}

export const APNS_TOKEN = "a".repeat(64)
export const FCM_TOKEN = "fcm-token_abc:DEF-123"

export async function makeEnv(overrides: Partial<Env> = {}) {
  const apns = await ecKey()
  const fcm = await rsaKey()
  const env = {
    RATE_LIMITER: rateLimiter(),
    APNS_TOPIC: "com.feernandooff.kinuvo",
    APNS_KEY_ID: "KEY123",
    APNS_TEAM_ID: "TEAM456",
    APNS_KEY: apns.pem,
    FCM_PROJECT_ID: "kinuvo-test",
    FCM_SERVICE_ACCOUNT: JSON.stringify({ client_email: "relay@kinuvo-test.iam.gserviceaccount.com", private_key: fcm.pem }),
    ...overrides,
  }
  return { env: env as unknown as Env, limiter: env.RATE_LIMITER as ReturnType<typeof rateLimiter>, apnsPublicKey: apns.publicKey, fcmPublicKey: fcm.publicKey }
}

export function push(path: string, init: { body?: BodyInit | null; headers?: Record<string, string>; method?: string } = {}) {
  return new Request(`https://push.kinuvo.dev${path}`, {
    method: init.method ?? "POST",
    headers: { "content-encoding": "aes128gcm", "content-type": "application/octet-stream", ttl: "60", urgency: "high", ...init.headers },
    body: init.body === undefined ? new Uint8Array([1, 2, 3, 250]) : init.body,
  })
}

export const json = (body: unknown, status = 200) =>
  new Response(JSON.stringify(body), { status, headers: { "content-type": "application/json" } })
