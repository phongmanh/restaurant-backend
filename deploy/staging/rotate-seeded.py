"""Rotates staging's seeded admin and manager A passwords after they were exposed (key policy).

    python3 -I deploy/staging/rotate-seeded.py

Signs each in (the admin through TOTP), changes the password with POST /v1/auth/password (which ends
their sessions) and, only after its 204, writes the new one into deploy/staging/.accounts.staging
(mode 600). Prints statuses only. The admin's TOTP secret is not rotated: that needs a second admin.
Run it yourself: the auto-mode safety check blocks Claude from changing live credentials.
"""
import base64
import hashlib
import hmac
import json
import os
import secrets
import struct
import sys
import time
import urllib.error
import urllib.request

env_file, accounts_file = "deploy/staging/.env.staging", "deploy/staging/.accounts.staging"
base = "https://restaurant-staging-liam-36c7.vercel.app"
bypass = next(l.split("=", 1)[1].strip() for l in open(env_file) if l.startswith("VERCEL_AUTOMATION_BYPASS_SECRET="))
accounts = json.load(open(accounts_file))


def call(path, body, token=None):
    req = urllib.request.Request(base + path, data=json.dumps(body).encode(), method="POST")
    req.add_header("x-vercel-protection-bypass", bypass)
    req.add_header("Content-Type", "application/json")
    if token:
        req.add_header("Authorization", "Bearer " + token)
    try:
        with urllib.request.urlopen(req, timeout=60) as r:
            return r.status, r.read()
    except urllib.error.HTTPError as e:
        return e.code, e.read()


def totp(secret_b32):
    key = base64.b32decode(secret_b32 + "=" * (-len(secret_b32) % 8))
    mac = hmac.new(key, struct.pack(">Q", int(time.time()) // 30), hashlib.sha1).digest()
    o = mac[-1] & 0x0F
    return "%06d" % ((struct.unpack(">I", mac[o:o + 4])[0] & 0x7FFFFFFF) % 1_000_000)


def save():
    tmp = accounts_file + ".tmp"
    fd = os.open(tmp, os.O_WRONLY | os.O_CREAT | os.O_TRUNC, 0o600)
    with os.fdopen(fd, "w") as f:
        json.dump(accounts, f, indent=4, ensure_ascii=False)
        f.write("\n")
    os.replace(tmp, accounts_file)


def rotate(label, account):
    s, b = call("/v1/auth/sign-in", {"email": account["email"], "password": account["password"]})
    body = json.loads(b) if s == 200 else {}
    print(label, "sign-in", s, "mfa" if body.get("mfa_required") else "")
    if body.get("mfa_required"):
        s, b = call("/v1/auth/mfa", {"mfa_token": body["mfa_token"], "code": totp(account["totp_secret"])})
        print(label, "mfa", s)
        body = json.loads(b) if s == 200 else {}
    token = body.get("access_token")
    if not token:
        return
    new = secrets.token_urlsafe(18)
    s, _ = call("/v1/auth/password", {"current_password": account["password"], "new_password": new}, token)
    print(label, "change", s)
    if s == 204:
        account["password"] = new
        save()
        s, _ = call("/v1/auth/sign-in", {"email": account["email"], "password": new})
        print(label, "sign-in with the new password", s)


rotate("admin", accounts["admin"])
rotate("manager A", accounts["restaurants"]["A"]["manager"])
