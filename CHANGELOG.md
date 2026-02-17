# 更新日志

## v1.0.2 (2025-02-17) [PC客户端]
- 修复：改用 parking_lot::Mutex 替代 tokio::sync::Mutex，解决 enigo 在异步上下文中可能导致崩溃的问题
- 修复：移除 panic = "abort"，让程序崩溃时产生有意义的错误日志
- 新增：添加文件日志功能，日志保存到 %LOCALAPPDATA%/voice-sync-pc/voice-sync.log
- 优化：enigo 操作改为同步调用，更稳定

## v1.0.2 (2025-02-16) [Android客户端]
- 新增：切回 APP 时自动弹出输入法（从其他应用或悬浮窗打开均生效）
- 优化：兼容 ColorOS 等定制 ROM 的 Activity 生命周期

## v1.0.1 (2025-02-14)
- 修复：修复 JSON 中换行符转义问题

## v1.0.0 (2025-02-13)
- 初始版本
- WebSocket 连接与自动发送
- 延迟发送 debounce 功能
