// RFC 8291 Appendix A.
let key = try! P256.KeyAgreement.PrivateKey(rawRepresentation: Data(base64URL: "q1dXpw3UpT5VOmu_cf_v6ih07Aems3njxI-JWgLcM94")!)
let keys = PushKeys(key: key, auth: Data(base64URL: "BTBZMqHH6r4Tts7J_aSIgg")!)
precondition(keys.p256dh.base64URL == "BCVxsr7N_eNgVRqvHtD0zTZsEc6-VV-JvLexhqUzORcxaOzi6-AYWXvTBHm4bjyPjs7Vd8pZGH6SRpkNtoIAiw4")
let record = Data(base64URL: "DGv6ra1nlYgDCS1FRnbzlwAAEABBBP4z9KsN6nGRTbVYI_c7VJSPQTBtkgcy27mlmlMoZIIgDll6e3vCYLocInmYWAmS6TlzAC8wEqKK6PBru3jl7A8")!
    + Data(base64URL: "8pfeW0KbunFT06SuDKoJH9Ql87S1QUrdirN6GcG7sFz1y1sqLgVi1VhjVkHsUoEsbI_0LpXMuGvnzQ")!
precondition(String(data: try! keys.open(record), encoding: .utf8) == "When I grow up, I want to be a watermelon")

var tampered = record
tampered[tampered.count - 1] ^= 1
precondition((try? keys.open(tampered)) == nil, "A changed record must not open")
precondition((try? keys.open(record.prefix(40))) == nil, "A short record must not open")
let other = PushKeys(key: P256.KeyAgreement.PrivateKey(), auth: keys.auth)
precondition((try? other.open(record)) == nil, "Another install's keys must not open the record")
print("RFC 8291 push decrypt: ok")
