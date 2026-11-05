"""Minimal ES256 VAPID verification (no third-party crypto in the container).

Verifies a VAPID JWT against the subscription's application_server_key, checks
`aud` against this relay's audience and `exp`/`nbf` with a small clock skew, and
exposes the key thumbprint so a registration can be bound to one VAPID key.
"""
import base64
import hashlib
import json
import time

# NIST P-256 parameters
P = 0xFFFFFFFF00000001000000000000000000000000FFFFFFFFFFFFFFFFFFFFFFFF
A = P - 3
B = 0x5AC635D8AA3A93E7B3EBBD55769886BC651D06B0CC53B0F63BCE3C3E27D2604B
N = 0xFFFFFFFF00000000FFFFFFFFFFFFFFFFBCE6FAADA7179E84F3B9CAC2FC632551
GX = 0x6B17D1F2E12C4247F8BCE6E563A440F277037D812DEB33A0F4A13945D898C296
GY = 0x4FE342E2FE1A7F9B8EE7EB4A7C0F9E162BCE33576B315ECECBB6406837BF51F5


def _inv(value, modulus):
    return pow(value, modulus - 2, modulus)


def _add(p1, p2):
    if p1 is None:
        return p2
    if p2 is None:
        return p1
    x1, y1 = p1
    x2, y2 = p2
    if x1 == x2 and (y1 + y2) % P == 0:
        return None
    if p1 == p2:
        lam = (3 * x1 * x1 + A) * _inv(2 * y1, P) % P
    else:
        lam = (y2 - y1) * _inv(x2 - x1, P) % P
    x3 = (lam * lam - x1 - x2) % P
    y3 = (lam * (x1 - x3) - y1) % P
    return (x3, y3)


def _mul(point, scalar):
    result = None
    addend = point
    while scalar:
        if scalar & 1:
            result = _add(result, addend)
        addend = _add(addend, addend)
        scalar >>= 1
    return result


def b64u_decode(text: str) -> bytes:
    pad = "=" * (-len(text) % 4)
    return base64.urlsafe_b64decode(text + pad)


def b64u_encode(raw: bytes) -> str:
    return base64.urlsafe_b64encode(raw).decode().rstrip("=")


def public_key_point(key_b64: str):
    raw = b64u_decode(key_b64)
    if len(raw) != 65 or raw[0] != 4:
        raise ValueError("application_server_key must be an uncompressed P-256 point")
    return (int.from_bytes(raw[1:33], "big"), int.from_bytes(raw[33:], "big"))


def key_thumbprint(key_b64: str) -> str:
    """RFC 7638 thumbprint (only crv/x/y are required members for EC keys)."""
    x, y = public_key_point(key_b64)
    raw_x = x.to_bytes(32, "big")
    raw_y = y.to_bytes(32, "big")
    canon = ('{"crv":"P-256","kty":"EC","x":"%s","y":"%s"}' % (b64u_encode(raw_x), b64u_encode(raw_y))).encode()
    return b64u_encode(hashlib.sha256(canon).digest())


def verify_vapid(authorization: str, audience: str, key_b64: str, now: float | None = None, skew: int = 300):
    """Return (ok, reason). Header form: `vapid t=<jwt>, k=<key>` (k optional)."""
    if not authorization or not authorization.lower().startswith("vapid"):
        return False, "missing VAPID authorization"
    params = {}
    for part in authorization[5:].split(","):
        if "=" in part:
            name, _, value = part.strip().partition("=")
            params[name] = value
    token = params.get("t")
    if not token:
        return False, "VAPID authorization has no token"
    try:
        header_b64, payload_b64, signature_b64 = token.split(".")
    except ValueError:
        return False, "VAPID token is not a JWT"
    try:
        header = json.loads(b64u_decode(header_b64))
        claims = json.loads(b64u_decode(payload_b64))
        signature = b64u_decode(signature_b64)
    except Exception:
        return False, "VAPID token is not decodable"
    if header.get("alg") != "ES256":
        return False, "VAPID alg must be ES256"
    if len(signature) != 64:
        return False, "VAPID signature length"
    try:
        x, y = public_key_point(key_b64)
    except ValueError as error:
        return False, str(error)
    digest = hashlib.sha256((header_b64 + "." + payload_b64).encode()).digest()
    r = int.from_bytes(signature[:32], "big")
    s = int.from_bytes(signature[32:], "big")
    if not (1 <= r < N and 1 <= s < N):
        return False, "VAPID signature out of range"
    w = _inv(s, N)
    u1 = int.from_bytes(digest, "big") * w % N
    u2 = r * w % N
    point = _add(_mul((GX, GY), u1), _mul((x, y), u2))
    if point is None:
        return False, "VAPID signature did not verify"
    if point[0] % N != r:
        return False, "VAPID signature did not verify"
    current = time.time() if now is None else now
    exp = claims.get("exp")
    if exp is None:
        return False, "VAPID token has no exp"
    if float(exp) + skew < current:
        return False, "VAPID token expired"
    if claims.get("nbf") is not None and float(claims["nbf"]) - skew > current:
        return False, "VAPID token is not valid yet"
    aud = claims.get("aud")
    audiences = aud if isinstance(aud, list) else [aud]
    if not any(str(item).rstrip("/") == audience.rstrip("/") for item in audiences if item):
        return False, "VAPID audience mismatch"
    sub = claims.get("sub")
    if not sub or not str(sub).startswith("mailto:") and not str(sub).startswith("https://"):
        return False, "VAPID subject must be a mailto: or https: URL"
    return True, "ok"
