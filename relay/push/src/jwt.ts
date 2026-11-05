const encoder = new TextEncoder()

export function base64url(data: ArrayBuffer | Uint8Array | string): string {
  const bytes = typeof data === "string" ? encoder.encode(data) : new Uint8Array(data)
  let binary = ""
  for (const byte of bytes) binary += String.fromCharCode(byte)
  return btoa(binary).replace(/\+/g, "-").replace(/\//g, "_").replace(/=+$/, "")
}

function pkcs8(pem: string): ArrayBuffer {
  const base64 = pem.replace(/-----[^-]+-----/g, "").replace(/\s+/g, "")
  return Uint8Array.from(atob(base64), (c) => c.charCodeAt(0)).buffer
}

const algorithms = {
  ES256: { import: { name: "ECDSA", namedCurve: "P-256" }, sign: { name: "ECDSA", hash: "SHA-256" } },
  RS256: { import: { name: "RSASSA-PKCS1-v1_5", hash: "SHA-256" }, sign: { name: "RSASSA-PKCS1-v1_5" } },
} as const

export async function signJwt(alg: keyof typeof algorithms, pem: string, header: object, claims: object): Promise<string> {
  const { import: importParams, sign } = algorithms[alg]
  const key = await crypto.subtle.importKey("pkcs8", pkcs8(pem), importParams, false, ["sign"])
  const input = `${base64url(JSON.stringify({ alg, typ: "JWT", ...header }))}.${base64url(JSON.stringify(claims))}`
  const signature = await crypto.subtle.sign(sign, key, encoder.encode(input))
  return `${input}.${base64url(signature)}`
}
