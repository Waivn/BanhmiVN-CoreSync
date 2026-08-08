"""
End-to-end verification of BanhmiVN-CoreSync <-> website API contract.

Launches the real FastAPI app (D:/Code/Website) with a THROWAWAY sqlite DB
on a local port, then exercises:
  POST /api/codes/sync      (plugin registers a self-generated code)
  POST /api/codes/redeem    (player claims it in-game; atomic one-time use)
  POST /api/server/status   (heartbeat telemetry merge)
  GET  /api/server/status   (public read-back)
No production data is touched. The temp DB is deleted afterwards.
"""
import json
import os
import sqlite3
import subprocess
import sys
import time
import urllib.error
import urllib.request

WEBSITE = r"D:/Code/Website"
PORT = 8899
BASE = f"http://127.0.0.1:{PORT}"
API_KEY = "e2e-test-key"
DB_PATH = os.path.join(WEBSITE, "_e2e_coresync.db")

PASS = 0
FAIL = 0


def check(name: str, cond: bool, extra: str = ""):
    global PASS, FAIL
    if cond:
        PASS += 1
        print(f"  PASS  {name}")
    else:
        FAIL += 1
        print(f"  FAIL  {name} {extra}")


def request(method: str, path: str, body=None, headers=None):
    data = json.dumps(body).encode() if body is not None else None
    req = urllib.request.Request(BASE + path, data=data, method=method,
                                 headers={"Content-Type": "application/json", **(headers or {})})
    try:
        with urllib.request.urlopen(req, timeout=15) as resp:
            raw = resp.read().decode()
            return resp.status, json.loads(raw) if raw else None
    except urllib.error.HTTPError as e:
        raw = e.read().decode()
        try:
            return e.code, json.loads(raw)
        except Exception:
            return e.code, raw


def main():
    # ── 1. Clean + boot the app on the throwaway DB ──
    if os.path.exists(DB_PATH):
        os.remove(DB_PATH)
    env = dict(os.environ)
    env.update({
        "DATABASE_URL": "sqlite:///./_e2e_coresync.db",
        "MC_API_KEY": API_KEY,
        "SECRET_KEY": "e2e-test-secret-key-not-for-production",
        "ENABLE_DEV_LOGIN": "true",
        "DEBUG": "true",
        "ALLOWED_ORIGINS": "*",
    })
    proc = subprocess.Popen(
        [sys.executable, "-m", "uvicorn", "app.main:app", "--host", "127.0.0.1",
         "--port", str(PORT), "--log-level", "warning"],
        cwd=WEBSITE, env=env,
        stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
    try:
        # wait for boot
        for _ in range(60):
            try:
                code, _ = request("GET", "/api/server/status")
                if code in (200, 401):
                    break
            except Exception:
                time.sleep(0.5)
        else:
            print("FAIL app did not boot"); sys.exit(1)
        print("[boot] app is up on", BASE)

        # ── 2. Create a user (needed as ShopOrder.user_id FK) and promote to admin ──
        code, resp = request("POST", "/auth/register", {
            "email": "e2e@banhmivn.fun",
            "password": "e2e-password-123",
            "username": "e2e",
            "minecraft_ign": "Steve",
        })
        check("register user", code == 200, f"got {code} {resp}")
        con = sqlite3.connect(DB_PATH)
        con.execute("UPDATE users SET is_admin = 1 WHERE email = 'e2e@banhmivn.fun'")
        con.commit()
        con.close()
        print("[setup] user promoted to admin")

        headers = {"X-API-Key": API_KEY}

        # ── 3. /api/codes/sync — plugin registers a generated code ──
        code, resp = request("POST", "/api/codes/sync", {
            "code": "BMVN-TEST-ABCD-EFGH",
            "player_name": "Steve",
            "items": [
                {"product_type": "rank", "product_name": "👑 Rank VIP+", "qty": 1},
                {"product_type": "point", "product_name": "💎 Đổi Point Server", "qty": 100},
            ],
        }, headers)
        check("sync OK", code == 200 and resp.get("success") and resp.get("order_id"),
              f"got {code} {resp}")
        order_id = (resp or {}).get("order_id")

        code, resp = request("POST", "/api/codes/sync", {
            "code": "BMVN-TEST-ABCD-EFGH",
            "items": [{"product_type": "point", "product_name": "x", "qty": 1}],
        }, headers)
        check("sync duplicate -> 409", code == 409, f"got {code}")

        code, resp = request("POST", "/api/codes/sync", {
            "code": "NOT-A-CODE",
            "items": [{"product_type": "point", "product_name": "x", "qty": 1}],
        }, headers)
        check("sync bad format -> 422", code == 422, f"got {code}")

        code, resp = request("POST", "/api/codes/sync", {
            "code": "BMVN-TEST-WXYZ-1234",
            "items": [{"product_type": "point", "product_name": "x", "qty": 1}],
        })
        check("sync missing key -> 401", code == 401, f"got {code}")

        # ── 4. /api/codes/redeem — in-game claim (one-time, atomic) ──
        code, resp = request("POST", "/api/codes/redeem", {
            "code": "bmvn-test-abcd-efgh",  # lowercase → server normalizes
            "player_name": "Steve",
            "ign": "Steve",
        }, headers)
        ok = (code == 200 and resp.get("status") == "used"
              and len(resp.get("items", [])) == 2
              and resp["items"][0]["product_type"] == "rank"
              and resp["items"][0]["product_name"] == "👑 Rank VIP+"
              and resp.get("order_code"))
        check("redeem first time OK + items echo", ok, f"got {code} {resp}")

        code, resp = request("POST", "/api/codes/redeem", {
            "code": "BMVN-TEST-ABCD-EFGH",
            "player_name": "Notch",
        }, headers)
        check("redeem again -> 409 already used", code == 409, f"got {code}")

        code, resp = request("POST", "/api/codes/redeem", {
            "code": "BMVN-NOPE-NOPE-NOPE",
            "player_name": "Steve",
        }, headers)
        check("redeem unknown -> 404", code == 404, f"got {code}")

        # ── 5. /api/server/status — heartbeat telemetry ──
        code, resp = request("POST", "/api/server/status", {
            "status": "online",
            "player_count": 12,
            "max_players": 100,
            "tps": 19.8,
            "memory_mb": 512,
        }, headers)
        check("status push OK", code == 200, f"got {code}")

        code, resp = request("GET", "/api/server/status")
        ok = (code == 200 and resp.get("status") == "online"
              and resp.get("player_count") == 12
              and resp.get("max_players") == 100
              and abs(resp.get("tps", 0) - 19.8) < 0.01)
        check("status read-back merged", ok, f"got {resp}")

        # heartbeat_at phải được stamp mỗi lần plugin đẩy (dùng cho widget freshness)
        hb = resp.get("heartbeat_at")
        check("heartbeat_at stamped", isinstance(hb, (int, float)) and hb > 0, f"hb={hb}")
        code, resp2 = request("POST", "/api/server/status", {
            "status": "online", "player_count": 13, "tps": 19.9,
        }, headers)
        code, resp3 = request("GET", "/api/server/status")
        hb2 = resp3.get("heartbeat_at")
        check("heartbeat_at refreshes on push",
              isinstance(hb2, (int, float)) and hb2 >= hb, f"hb={hb} hb2={hb2}")

        # maintenance state mapping (plugin maps CLOSED -> offline)
        code, resp = request("POST", "/api/server/status", {
            "status": "offline", "player_count": 0,
        }, headers)
        code, resp = request("GET", "/api/server/status")
        check("status offline state", code == 200 and resp.get("status") == "offline", f"got {resp}")

        code, resp = request("POST", "/api/server/status", {
            "status": "hacker", "player_count": 0,
        }, headers)
        check("status invalid -> 422", code == 422, f"got {code}")

        print(f"\n===== E2E RESULT: {PASS} passed, {FAIL} failed =====")
        return 0 if FAIL == 0 else 1
    finally:
        proc.terminate()
        try:
            proc.wait(timeout=10)
        except Exception:
            proc.kill()
        if os.path.exists(DB_PATH):
            os.remove(DB_PATH)
        for suffix in ("-shm", "-wal"):
            p = DB_PATH + suffix
            if os.path.exists(p):
                os.remove(p)
        print("[cleanup] test server stopped, temp DB removed")


if __name__ == "__main__":
    sys.exit(main())
