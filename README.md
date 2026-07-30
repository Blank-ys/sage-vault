# sage-vault

## 安装bge-m3本地模型
```python
from modelscope import snapshot_download

model_dir = snapshot_download('BAAI/bge-m3', cache_dir="F:\\tmp\\models")
```

## 启动命令

### 前端启动
```
cd frontend
yarn dev
```

### python模块启动
```
cd ai-modules/services/rag
uv run uvicorn sage_vault_rag.main:app --host 127.0.0.1 --port 8000
```