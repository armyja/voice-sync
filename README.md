# VoiceSync

手机语音输入 → 电脑自动输入

将手机输入的文字实时同步到电脑当前活动窗口。

**仅支持安卓手机 → Windows 电脑**

<img src="https://github.com/user-attachments/assets/7a4693e6-1913-40d2-ac8d-9ec6d797d6bf" width="200">

## 功能特点

- 📱 手机语音输入，自动发送到电脑
- 💻 电脑端自动粘贴到当前活动窗口
- 🔄 支持自动重连
- ⚙️ 可调节发送延迟 (400-800ms)
- 📋 发送后自动还原剪贴板内容

## 项目结构

```
voice-sync-mobile/   # Android 客户端
voice-sync-pc/       # Rust PC 端 (含 WebSocket 服务器)
```

## 快速开始

### PC 端

#### 运行源码

```bash
cd voice-sync-pc
cargo run --release
```

#### 命令行参数

```bash
# 默认配置 (0.0.0.0:8765)
voice-sync-pc

# 自定义端口
voice-sync-pc --port 9000

# 自定义 IP 和端口
voice-sync-pc --ip 192.168.1.100 --port 8765
```

#### 预编译版本

从 releases 目录获取预编译的 exe 文件和 apk 安装包。

### Android 端

1. 安装 APK (`voice-sync-mobile/releases/VoiceSync.apk`)
2. 输入 PC 的 IP 地址（如 `ws://192.168.1.100:8765`）
3. 点击连接
4. 在下方输入框弹出输入法后说话

#### 调节延迟

拖动滑块调整发送延迟：
- 范围：400ms - 800ms
- 默认：600ms
- 延迟越短发送越快，但可能因语音识别未完成而漏字

## 工作原理

1. Android 端通过 WebSocket 发送 JSON 消息
2. PC 端接收消息，解析 content 字段
3. PC 端将文字复制到剪贴板
4. 模拟 Ctrl+V 粘贴到当前活动窗口
5. 还原剪贴板原有内容

## 消息格式

```json
{"type": "register", "device": "mobile", "name": "手机"}
{"type": "text", "content": "要发送的文字"}
```

## 技术栈

- **Android**: Kotlin, Java-WebSocket
- **PC**: Rust, tokio, tokio-tungstenite, enigo, arboard

## 编译

### Android

```bash
cd voice-sync-mobile
./gradlew assembleRelease
```

APK 输出目录: `app/build/outputs/apk/release/`

### PC (Rust)

```bash
cd voice-sync-pc
cargo build --release
```

exe 输出目录: `target/release/voice-sync-pc.exe`

## 注意事项

- PC 和手机需在同一局域网
- Windows 防火墙可能阻止端口访问
- 部分杀毒软件可能拦截模拟按键
