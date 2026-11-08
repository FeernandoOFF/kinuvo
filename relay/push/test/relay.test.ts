import { afterEach, beforeEach, describe, expect, it, vi } from "vitest"
import type { Env } from "../src/index"
import { APNS_TOKEN, FCM_TOKEN, decodeJwt, fromBase64url, json, makeEnv, push } from "./helpers"

type Handler = { fetch(request: Request, env: Env): Promise<Response> }

let relay: Handler
let upstream: ReturnType<typeof vi.fn>

const call = (request: Request, env: Env) => relay.fetch(request, env)
const lastRequest = () => upstream.mock.calls.at(-1) as [string, RequestInit]
const requestsTo = (prefix: string) => upstream.mock.calls.filter(([url]) => String(url).startsWith(prefix)) as [string, RequestInit][]

function respond(handler: (url: string, init: RequestInit) => Response) {
  upstream.mockImplementation(async (url: string, init: RequestInit) => handler(url, init))
}

const oauth = (url: string) => url === "https://oauth2.googleapis.com/token" ? json({ access_token: "access-1", expires_in: 3600 }) : undefined

beforeEach(async () => {
  vi.resetModules()
  vi.useFakeTimers({ toFake: ["Date"] })
  vi.setSystemTime(new Date("2026-10-31T12:00:00Z"))
  vi.spyOn(console, "warn").mockImplementation(() => {})
  vi.spyOn(console, "error").mockImplementation(() => {})
  upstream = vi.fn(async () => new Response(null, { status: 200 }))
  vi.stubGlobal("fetch", upstream)
  relay = (await import("../src/index")).default as Handler
})

afterEach(() => {
  vi.useRealTimers()
  vi.unstubAllGlobals()
  vi.restoreAllMocks()
})

describe("routing and validation", () => {
  it.each([
    "/", "/v1", "/v2/fcm/abc", "/v1/fcm", "/v1/fcm/abc/extra", "/v1/apns/staging/" + APNS_TOKEN, "/v1/apns/production", "/v1/webpush/abc",
  ])("404 for %s", async (path) => {
    const { env } = await makeEnv()
    expect((await call(push(path), env)).status).toBe(404)
  })

  it("405 for non-POST", async () => {
    const { env } = await makeEnv()
    const response = await call(push(`/v1/fcm/${FCM_TOKEN}`, { method: "GET", body: null }), env)
    expect(response.status).toBe(405)
    expect(response.headers.get("allow")).toBe("POST")
  })

  it("400 without aes128gcm encoding", async () => {
    const { env } = await makeEnv()
    expect((await call(push(`/v1/fcm/${FCM_TOKEN}`, { headers: { "content-encoding": "aesgcm" } }), env)).status).toBe(400)
  })

  it.each([
    ["apns", "/v1/apns/production/" + "a".repeat(63)],
    ["apns", "/v1/apns/production/" + "a".repeat(201)],
    ["apns", "/v1/apns/sandbox/" + "g".repeat(64)],
    ["fcm", "/v1/fcm/" + "a".repeat(4097)],
    ["fcm", "/v1/fcm/bad%20token"],
    ["fcm", "/v1/fcm/bad.token"],
  ])("400 for malformed %s token", async (_, path) => {
    const { env } = await makeEnv()
    expect((await call(push(path), env)).status).toBe(400)
  })

  it("accepts boundary token lengths", async () => {
    const { env } = await makeEnv()
    respond((url) => oauth(url) ?? new Response(null, { status: 200 }))
    expect((await call(push("/v1/apns/production/" + "F".repeat(200)), env)).status).toBe(201)
    expect((await call(push("/v1/fcm/" + "a".repeat(4096)), env)).status).toBe(201)
  })

  it("400 for empty body, 413 above 3 KiB", async () => {
    const { env } = await makeEnv()
    expect((await call(push(`/v1/fcm/${FCM_TOKEN}`, { body: new Uint8Array(0) }), env)).status).toBe(400)
    expect((await call(push(`/v1/fcm/${FCM_TOKEN}`, { body: new Uint8Array(3073) }), env)).status).toBe(413)
    expect(upstream).not.toHaveBeenCalled()
  })

  it("accepts a 3 KiB body", async () => {
    const { env } = await makeEnv()
    respond((url) => oauth(url) ?? new Response(null, { status: 200 }))
    expect((await call(push(`/v1/fcm/${FCM_TOKEN}`, { body: new Uint8Array(3072) }), env)).status).toBe(201)
  })

  it("503 when a provider is not configured", async () => {
    const { env } = await makeEnv({ APNS_KEY: undefined, FCM_SERVICE_ACCOUNT: "" })
    expect((await call(push(`/v1/apns/production/${APNS_TOKEN}`), env)).status).toBe(503)
    expect((await call(push(`/v1/fcm/${FCM_TOKEN}`), env)).status).toBe(503)
    expect(upstream).not.toHaveBeenCalled()
  })
})

describe("rate limit", () => {
  it("allows 30 per token, then 429, keyed by a hash of the token", async () => {
    const { env, limiter } = await makeEnv()
    const statuses = []
    for (let i = 0; i < 31; i++) statuses.push((await call(push(`/v1/apns/production/${APNS_TOKEN}`), env)).status)
    expect(statuses.slice(0, 30).every((it) => it === 201)).toBe(true)
    expect(statuses[30]).toBe(429)
    expect(upstream).toHaveBeenCalledTimes(30)
    expect(limiter.keys[0]).toMatch(/^[0-9a-f]{64}$/)
    expect(limiter.keys[0]).not.toContain(APNS_TOKEN)

    expect((await call(push(`/v1/apns/production/${"b".repeat(64)}`), env)).status).toBe(201)
  })
})

describe("APNs", () => {
  it("sends the encrypted record with a signed provider token", async () => {
    const { env, apnsPublicKey } = await makeEnv()
    const body = new Uint8Array([0, 1, 2, 0xfb, 0xff])
    expect((await call(push(`/v1/apns/production/${"AB".repeat(32)}`, { body, headers: { ttl: "120" } }), env)).status).toBe(201)

    const [url, init] = lastRequest()
    expect(url).toBe(`https://api.push.apple.com/3/device/${"ab".repeat(32)}`)
    expect(init.method).toBe("POST")
    const headers = init.headers as Record<string, string>
    expect(headers["apns-topic"]).toBe("com.feernandooff.kinuvo")
    expect(headers["apns-push-type"]).toBe("alert")
    expect(headers["apns-priority"]).toBe("10")
    expect(headers["apns-expiration"]).toBe(String(Date.parse("2026-10-31T12:00:00Z") / 1000 + 120))
    const payload = JSON.parse(init.body as string)
    expect(payload.aps).toEqual({ alert: { title: "Kinuvo", body: "New activity" }, "mutable-content": 1, sound: "default" })
    expect(fromBase64url(payload.k)).toEqual(body)
    expect(payload.k).not.toMatch(/[+/=]/)

    const jwt = decodeJwt(headers.authorization.replace(/^bearer /, ""))
    expect(jwt.header).toEqual({ alg: "ES256", typ: "JWT", kid: "KEY123" })
    expect(jwt.claims).toEqual({ iss: "TEAM456", iat: Date.parse("2026-10-31T12:00:00Z") / 1000 })
    const valid = await crypto.subtle.verify({ name: "ECDSA", hash: "SHA-256" }, apnsPublicKey, jwt.signature, new TextEncoder().encode(jwt.input))
    expect(valid).toBe(true)
  })

  it("uses the sandbox host and normal priority", async () => {
    const { env } = await makeEnv()
    await call(push(`/v1/apns/sandbox/${APNS_TOKEN}`, { headers: { urgency: "normal" } }), env)
    const [url, init] = lastRequest()
    expect(url).toBe(`https://api.sandbox.push.apple.com/3/device/${APNS_TOKEN}`)
    expect((init.headers as Record<string, string>)["apns-priority"]).toBe("5")
  })

  it("caches the provider token for about 50 minutes", async () => {
    const { env } = await makeEnv()
    const auth = () => (lastRequest()[1].headers as Record<string, string>).authorization
    await call(push(`/v1/apns/production/${APNS_TOKEN}`), env)
    const first = auth()
    vi.setSystemTime(Date.now() + 49 * 60 * 1000)
    await call(push(`/v1/apns/production/${APNS_TOKEN}`), env)
    expect(auth()).toBe(first)
    vi.setSystemTime(Date.now() + 2 * 60 * 1000)
    await call(push(`/v1/apns/production/${APNS_TOKEN}`), env)
    expect(auth()).not.toBe(first)
  })

  it("re-signs after APNs rejects an expired provider token", async () => {
    const { env } = await makeEnv()
    const auth = () => (lastRequest()[1].headers as Record<string, string>).authorization
    respond(() => json({ reason: "ExpiredProviderToken" }, 403))
    expect((await call(push(`/v1/apns/production/${APNS_TOKEN}`), env)).status).toBe(502)
    const first = auth()
    vi.setSystemTime(Date.now() + 1000)
    respond(() => new Response(null, { status: 200 }))
    await call(push(`/v1/apns/production/${APNS_TOKEN}`), env)
    expect(auth()).not.toBe(first)
  })

  it.each([
    [json({ reason: "Unregistered" }, 410), 410],
    [json({ reason: "BadDeviceToken" }, 400), 410],
    [json({ reason: "DeviceTokenNotForTopic" }, 400), 410],
    [json({ reason: "BadCollapseId" }, 400), 502],
    [json({ reason: "TooManyRequests" }, 429), 429],
    [json({ reason: "InternalServerError" }, 500), 502],
    [new Response("not json", { status: 503 }), 502],
  ])("maps upstream %# to %i", async (response, expected) => {
    const { env } = await makeEnv()
    respond(() => response)
    expect((await call(push(`/v1/apns/production/${APNS_TOKEN}`), env)).status).toBe(expected)
  })

  it("502 when the network fails, without logging the token", async () => {
    const { env } = await makeEnv()
    upstream.mockRejectedValue(new Error("Network connection lost."))
    expect((await call(push(`/v1/apns/production/${APNS_TOKEN}`), env)).status).toBe(502)
    const logged = JSON.stringify([...vi.mocked(console.error).mock.calls, ...vi.mocked(console.warn).mock.calls])
    expect(logged).not.toContain(APNS_TOKEN)
  })
})

describe("FCM", () => {
  it("sends a data message with an OAuth token from the service account", async () => {
    const { env, fcmPublicKey } = await makeEnv()
    respond((url) => oauth(url) ?? json({ name: "projects/kinuvo-test/messages/1" }))
    const body = new Uint8Array([9, 8, 7, 0xfe])
    expect((await call(push(`/v1/fcm/${FCM_TOKEN}`, { body, headers: { ttl: "300" } }), env)).status).toBe(201)

    const [[, tokenInit]] = requestsTo("https://oauth2.googleapis.com/token")
    const form = new URLSearchParams(String(tokenInit.body))
    expect(form.get("grant_type")).toBe("urn:ietf:params:oauth:grant-type:jwt-bearer")
    const jwt = decodeJwt(form.get("assertion")!)
    expect(jwt.header).toEqual({ alg: "RS256", typ: "JWT" })
    const iat = Date.parse("2026-10-31T12:00:00Z") / 1000
    expect(jwt.claims).toEqual({
      iss: "relay@kinuvo-test.iam.gserviceaccount.com",
      scope: "https://www.googleapis.com/auth/firebase.messaging",
      aud: "https://oauth2.googleapis.com/token",
      iat,
      exp: iat + 3600,
    })
    const valid = await crypto.subtle.verify("RSASSA-PKCS1-v1_5", fcmPublicKey, jwt.signature, new TextEncoder().encode(jwt.input))
    expect(valid).toBe(true)

    const [url, init] = lastRequest()
    expect(url).toBe("https://fcm.googleapis.com/v1/projects/kinuvo-test/messages:send")
    expect((init.headers as Record<string, string>).authorization).toBe("Bearer access-1")
    const { message } = JSON.parse(init.body as string)
    expect(message.token).toBe(FCM_TOKEN)
    expect(message.android).toEqual({ priority: "HIGH", ttl: "300s" })
    expect(Object.keys(message.data)).toEqual(["k"])
    expect(fromBase64url(message.data.k)).toEqual(body)
    expect(message.notification).toBeUndefined()
  })

  it("uses normal priority when not urgent", async () => {
    const { env } = await makeEnv()
    respond((url) => oauth(url) ?? json({}))
    await call(push(`/v1/fcm/${FCM_TOKEN}`, { headers: { urgency: "low" } }), env)
    expect(JSON.parse(lastRequest()[1].body as string).message.android.priority).toBe("NORMAL")
  })

  it("caches the access token until shortly before it expires", async () => {
    const { env } = await makeEnv()
    respond((url) => oauth(url) ?? json({}))
    await call(push(`/v1/fcm/${FCM_TOKEN}`), env)
    vi.setSystemTime(Date.now() + 50 * 60 * 1000)
    await call(push(`/v1/fcm/${FCM_TOKEN}`), env)
    expect(requestsTo("https://oauth2.googleapis.com/token")).toHaveLength(1)
    vi.setSystemTime(Date.now() + 6 * 60 * 1000)
    await call(push(`/v1/fcm/${FCM_TOKEN}`), env)
    expect(requestsTo("https://oauth2.googleapis.com/token")).toHaveLength(2)
  })

  it("shares one token exchange between concurrent pushes", async () => {
    const { env } = await makeEnv()
    respond((url) => oauth(url) ?? json({}))
    await Promise.all([call(push(`/v1/fcm/${FCM_TOKEN}`), env), call(push(`/v1/fcm/${FCM_TOKEN}`), env)])
    expect(requestsTo("https://oauth2.googleapis.com/token")).toHaveLength(1)
  })

  it("502 and retries the exchange next time when OAuth fails", async () => {
    const { env } = await makeEnv()
    respond(() => json({ error: "invalid_grant" }, 400))
    expect((await call(push(`/v1/fcm/${FCM_TOKEN}`), env)).status).toBe(502)
    respond((url) => oauth(url) ?? json({}))
    expect((await call(push(`/v1/fcm/${FCM_TOKEN}`), env)).status).toBe(201)
  })

  it("502 for an invalid service account secret", async () => {
    const { env } = await makeEnv({ FCM_SERVICE_ACCOUNT: "{not json" })
    expect((await call(push(`/v1/fcm/${FCM_TOKEN}`), env)).status).toBe(502)
    expect(JSON.stringify(vi.mocked(console.error).mock.calls)).not.toContain("not json")
  })

  const fcmError = (status: number, code: string, message = "", errorCode?: string) =>
    json({ error: { code: status, status: code, message, details: errorCode ? [{ "@type": "type.googleapis.com/google.firebase.fcm.v1.FcmError", errorCode }] : [] } }, status)

  it.each([
    [() => fcmError(404, "NOT_FOUND", "Requested entity was not found.", "UNREGISTERED"), 410],
    [() => fcmError(400, "INVALID_ARGUMENT", "The registration token is not a valid FCM registration token", "INVALID_ARGUMENT"), 410],
    [() => fcmError(400, "INVALID_ARGUMENT", "Invalid value at 'message.android.ttl'"), 502],
    [() => fcmError(429, "RESOURCE_EXHAUSTED", "", "QUOTA_EXCEEDED"), 429],
    [() => fcmError(403, "PERMISSION_DENIED", "", "SENDER_ID_MISMATCH"), 502],
    [() => fcmError(503, "UNAVAILABLE"), 502],
  ])("maps upstream %# to %i", async (response, expected) => {
    const { env } = await makeEnv()
    respond((url) => oauth(url) ?? response())
    expect((await call(push(`/v1/fcm/${FCM_TOKEN}`), env)).status).toBe(expected)
  })

  it("refreshes the access token after a 401", async () => {
    const { env } = await makeEnv()
    respond((url) => oauth(url) ?? fcmError(401, "UNAUTHENTICATED"))
    expect((await call(push(`/v1/fcm/${FCM_TOKEN}`), env)).status).toBe(502)
    respond((url) => oauth(url) ?? json({}))
    await call(push(`/v1/fcm/${FCM_TOKEN}`), env)
    expect(requestsTo("https://oauth2.googleapis.com/token")).toHaveLength(2)
  })
})
