export interface Env {
  RATE_LIMITER: RateLimit
  APNS_TOPIC?: string
  APNS_KEY_ID?: string
  APNS_TEAM_ID?: string
  APNS_KEY?: string
  FCM_PROJECT_ID?: string
  FCM_SERVICE_ACCOUNT?: string
}

export interface Push {
  token: string
  body: Uint8Array
  ttl: number
  urgent: boolean
}

export type Outcome = "sent" | "gone" | "throttled" | "failed"
