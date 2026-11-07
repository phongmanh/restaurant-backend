"""Menu photo smoke check (FR-022, menu section 6) on an environment, after a release.

    python3 -I deploy/photo-smoke.py staging|production

As restaurant A's manager: uploads a generated PNG to a dish without a photo, reads both public
JPEGs, checks the menu shows the photo, then removes it, so the menu is left as it was (the
retention job deletes the objects after 30 days). Prints statuses only, never a secret. On
production run it yourself: the auto-mode safety check blocks Claude from writing there.
"""
import json
import struct
import sys
import urllib.error
import urllib.request
import zlib

env = sys.argv[1] if len(sys.argv) == 2 else ""
if env not in ("staging", "production"):
    sys.exit("usage: python3 -I deploy/photo-smoke.py staging|production")
env_file, accounts_file = f"deploy/{env}/.env.{env}", f"deploy/{env}/.accounts.{env}"
base = f"https://restaurant-{env}-liam-36c7.vercel.app"
bypass = next(l.split("=", 1)[1].strip() for l in open(env_file) if l.startswith("VERCEL_AUTOMATION_BYPASS_SECRET="))
manager = json.load(open(accounts_file))["restaurants"]["A"]["manager"]


def call(method, path, body=None, ctype="application/json", token=None, url=None):
    data = body if isinstance(body, bytes) or body is None else json.dumps(body).encode()
    req = urllib.request.Request(url or base + path, data=data, method=method)
    if url is None:
        req.add_header("x-vercel-protection-bypass", bypass)
    if data is not None:
        req.add_header("Content-Type", ctype)
    if token:
        req.add_header("Authorization", "Bearer " + token)
    try:
        with urllib.request.urlopen(req, timeout=60) as r:
            return r.status, r.headers, r.read()
    except urllib.error.HTTPError as e:
        return e.code, e.headers, e.read()


def png(w, h):
    raw = b"".join(b"\x00" + b"".join(bytes((x * 255 // w, y * 255 // h, 128)) for x in range(w)) for y in range(h))
    chunk = lambda t, d: struct.pack(">I", len(d)) + t + d + struct.pack(">I", zlib.crc32(t + d) & 0xFFFFFFFF)
    return b"\x89PNG\r\n\x1a\n" + chunk(b"IHDR", struct.pack(">IIBBBBB", w, h, 8, 2, 0, 0, 0)) + chunk(b"IDAT", zlib.compress(raw)) + chunk(b"IEND", b"")


s, _, b = call("POST", "/v1/auth/sign-in", {"email": manager["email"], "password": manager["password"]})
print("sign-in", s)
token = json.loads(b)["access_token"]

s, _, b = call("GET", "/v1/staff/menu", token=token)
menu = json.loads(b)
items = [i for c in menu.get("categories", []) for i in c.get("items", [])] or menu.get("items", [])
item = next(i for i in items if not i.get("image"))
print("menu", s, "items", len(items), "picked one without a photo")

s, _, b = call("PUT", f"/v1/staff/menu/items/{item['id']}/image", png(1200, 900), "image/png", token)
print("upload", s)
image = json.loads(b).get("image") if s == 200 else None
print("upload body", b[:200].decode(errors="replace") if s != 200 else "image urls present: " + str(bool(image and image.get("url") and image.get("thumb_url"))))

if image:
    for key in ("url", "thumb_url"):
        s2, h, body = call("GET", None, url=image[key])
        print(key, s2, h.get("Content-Type"), len(body), "bytes", "jpeg" if body[:3] == b"\xff\xd8\xff" else "NOT jpeg")
    s, _, b = call("GET", "/v1/staff/menu", token=token)
    after = [i for c in json.loads(b).get("categories", []) for i in c.get("items", [])]
    print("menu shows the photo", any(i["id"] == item["id"] and i.get("image") for i in after))

s, _, _ = call("DELETE", f"/v1/staff/menu/items/{item['id']}/image", token=token)
print("remove", s)
s, _, b = call("GET", "/v1/staff/menu", token=token)
after = [i for c in json.loads(b).get("categories", []) for i in c.get("items", [])]
print("photo gone from the menu", not any(i["id"] == item["id"] and i.get("image") for i in after))
