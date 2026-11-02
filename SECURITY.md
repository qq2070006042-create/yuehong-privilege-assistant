# 安全与隐私

## 配置文件

不要提交 `server.properties`、`keystore.properties`、密钥库、私钥、令牌、生产证书、真实设备日志或包含个人信息的 OTA 请求记录。公开仓库只保留字段为空的 `server.properties.example`。

所有生产接口应使用 HTTPS。V2 响应验签公钥可以进入构建产物，但服务端私钥只能保存在服务端密钥管理环境中，不应放入 Android 工程或源码仓库。

## 发布前检查

运行：

```powershell
pwsh -NoProfile -File .\tools\verify_public_source.ps1
```

检查通过后再创建源码归档。若任何凭据曾进入公开历史，应立即轮换对应凭据，并从仓库历史中清除。
