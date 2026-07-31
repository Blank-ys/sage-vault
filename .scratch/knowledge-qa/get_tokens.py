"""Helper: login via Gateway captcha flow and print tokens."""
import json
import socket
import urllib.request

GATEWAY = "http://192.168.150.100:8899"
REDIS_HOST = "192.168.150.100"
REDIS_PORT = 6379


def get_captcha_uuid() -> str:
    req = urllib.request.Request(f"{GATEWAY}/code")
    with urllib.request.urlopen(req, timeout=10) as resp:
        data = json.loads(resp.read().decode())
    return data["uuid"]


def read_redis(key: str) -> str:
    s = socket.socket()
    s.settimeout(5)
    s.connect((REDIS_HOST, REDIS_PORT))
    s.sendall(f"GET {key}\r\n".encode())
    data = s.recv(4096).decode()
    s.close()
    lines = data.strip().split("\r\n")
    if len(lines) > 1:
        return lines[1].strip('"')
    return ""


def login(username: str, password: str) -> str:
    uuid = get_captcha_uuid()
    code = read_redis(f"captcha_codes:{uuid}")
    body = json.dumps({
        "username": username,
        "password": password,
        "code": code,
        "uuid": uuid,
    }).encode()
    req = urllib.request.Request(
        f"{GATEWAY}/auth/login",
        data=body,
        headers={"Content-Type": "application/json"},
    )
    with urllib.request.urlopen(req, timeout=10) as resp:
        result = json.loads(resp.read().decode())
    if result.get("code") != 200:
        raise RuntimeError(f"Login failed: {result}")
    return result["data"]["access_token"]


if __name__ == "__main__":
    token = login("admin", "admin123")
    print(f"TOKEN={token}")
