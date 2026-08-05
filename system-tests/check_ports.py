import socket

targets = [
    ("192.168.150.100", 8899, "Gateway"),
    ("192.168.150.100", 3306, "MySQL"),
    ("192.168.150.100", 6379, "Redis"),
    ("192.168.150.100", 9000, "MinIO"),
    ("192.168.150.100", 9091, "Milvus"),
]

for host, port, name in targets:
    s = socket.socket()
    s.settimeout(5)
    result = s.connect_ex((host, port))
    status = "OPEN" if result == 0 else f"REFUSED (code={result})"
    print(f"{name} ({host}:{port}): {status}")
    s.close()
