use arboard::Clipboard;
use clap::Parser;
use enigo::{Enigo, Key, Direction, Keyboard, Settings};
use futures_util::StreamExt;
use log::{error, info};
use parking_lot::Mutex;
use serde::Deserialize;
use simplelog::{CombinedLogger, WriteLogger, LevelFilter, Config};
use std::fs::{self, OpenOptions};
use std::sync::Arc;
use std::net::{IpAddr, Ipv4Addr};
use std::path::PathBuf;
use tokio::net::{TcpListener, TcpStream};
use tokio_tungstenite::{accept_async, tungstenite::Message};

#[derive(Parser, Debug)]
#[command(author, version, about, long_about = None)]
struct Args {
    #[arg(long, default_value = "0.0.0.0")]
    ip: String,
    
    #[arg(short, long, default_value_t = 8765)]
    port: u16,
}

#[derive(Deserialize)]
struct JsonMessage {
    #[serde(rename = "type")]
    msg_type: String,
    content: Option<String>,
    device: Option<String>,
}

struct AppState {
    enigo: Mutex<Enigo>,
}

impl AppState {
    fn new() -> Result<Self, String> {
        let enigo = Enigo::new(&Settings::default()).map_err(|e| e.to_string())?;
        Ok(Self {
            enigo: Mutex::new(enigo),
        })
    }

    fn paste_text(&self) -> Result<(), String> {
        let mut enigo = self.enigo.lock();
        enigo.key(Key::Control, Direction::Press).map_err(|e| e.to_string())?;
        enigo.key(Key::Unicode('v'), Direction::Click).map_err(|e| e.to_string())?;
        enigo.key(Key::Control, Direction::Release).map_err(|e| e.to_string())?;
        Ok(())
    }
}

fn get_log_path() -> PathBuf {
    let mut path = dirs_next::data_local_dir().unwrap_or_else(|| PathBuf::from("."));
    path.push("voice-sync-pc");
    fs::create_dir_all(&path).ok();
    path.push("voice-sync.log");
    path
}

fn get_local_ips() -> Vec<IpAddr> {
    let mut ips = Vec::new();
    
    // 尝试获取本机实际 IP
    if let Ok(interface) = find_local_ip() {
        ips.push(interface);
    }
    
    // 添加本地回环地址
    ips.push(IpAddr::V4(Ipv4Addr::new(127, 0, 0, 1)));
    ips.push(IpAddr::V4(Ipv4Addr::new(0, 0, 0, 0)));
    
    // 去重
    ips.sort();
    ips.dedup();
    ips
}

fn find_local_ip() -> Result<IpAddr, std::io::Error> {
    use std::net::UdpSocket;
    
    // 尝试连接外部地址来获取本地 IP
    let socket = UdpSocket::bind("0.0.0.0:0")?;
    socket.connect("8.8.8.8:80")?;
    let addr = socket.local_addr()?;
    Ok(addr.ip())
}

#[tokio::main]
async fn main() {
    let args = Args::parse();

    let log_path = get_log_path();
    
    let file = OpenOptions::new()
        .create(true)
        .append(true)
        .open(&log_path)
        .expect("无法打开日志文件");
    
    CombinedLogger::init(vec![
        WriteLogger::new(LevelFilter::Info, Config::default(), file),
    ]).expect("无法初始化日志");
    
    info!("[+] 日志文件: {:?}", log_path);

    print_banner();
    
    let local_ips = get_local_ips();
    println!("[+] 可用地址:");
    for ip in &local_ips {
        println!("    {}:{}", ip, args.port);
    }
    println!();

    let state = match AppState::new() {
        Ok(s) => Arc::new(s),
        Err(e) => {
            error!("[-] 初始化键盘失败: {}", e);
            return;
        }
    };

    let addr = format!("{}:{}", args.ip, args.port);
    let listener = match TcpListener::bind(&addr).await {
        Ok(l) => l,
        Err(e) => {
            error!("[-] 绑定端口 {} 失败: {}", addr, e);
            return;
        }
    };

    info!("[+] 服务器启动成功，监听 {}", addr);

    while let Ok((stream, peer_addr)) = listener.accept().await {
        let state = state.clone();
        tokio::spawn(handle_connection(stream, peer_addr, state));
    }
}

fn print_banner() {
    println!();
    println!("╔═══════════════════════════════════════╗");
    println!("║       VoiceSync - 语音同步            ║");
    println!("║           (Rust 版本)                 ║");
    println!("╚═══════════════════════════════════════╝");
    println!();
}

async fn handle_connection(stream: TcpStream, peer_addr: std::net::SocketAddr, state: Arc<AppState>) {
    info!("[+] 客户端连接: {}", peer_addr);

    let ws_stream = match accept_async(stream).await {
        Ok(ws) => ws,
        Err(e) => {
            error!("[-] WebSocket 握手失败: {}", e);
            return;
        }
    };

    let (_, mut read) = ws_stream.split();
    info!("[+] WebSocket 连接建立");

    while let Some(msg) = read.next().await {
        match msg {
            Ok(Message::Text(text)) => {
                let parsed: Result<JsonMessage, _> = serde_json::from_str(&text);
                match parsed {
                    Ok(json_msg) => {
                        if json_msg.msg_type == "text" {
                            if let Some(content) = json_msg.content {
                                let trimmed = content.trim();
                                if !trimmed.is_empty() {
                                    handle_text(&state, trimmed).await;
                                }
                            }
                        } else if json_msg.msg_type == "register" {
                            let device = json_msg.device.unwrap_or_default();
                            info!("[+] 手机端注册: {}", device);
                        }
                    }
                    Err(_) => {
                        let trimmed = text.trim();
                        if !trimmed.is_empty() {
                            handle_text(&state, trimmed).await;
                        }
                    }
                }
            }
            Ok(Message::Close(_)) => {
                info!("[-] 客户端断开连接");
                break;
            }
            Err(e) => {
                error!("[-] 消息解析错误: {}", e);
                break;
            }
            _ => {}
        }
    }

    info!("[-] 连接关闭: {}", peer_addr);
}

async fn handle_text(state: &Arc<AppState>, text: &str) {
    info!("[收到] {} 字", text.chars().count());
    
    let original_clipboard = get_clipboard_text();
    
    if let Err(e) = copy_to_clipboard(text) {
        error!("[-] 剪贴板错误: {}", e);
        return;
    }

    tokio::time::sleep(tokio::time::Duration::from_millis(50)).await;

    if let Err(e) = state.paste_text() {
        error!("[-] 粘贴失败: {}", e);
    } else {
        let preview = if text.chars().count() > 15 {
            format!("{}...", text.chars().take(15).collect::<String>())
        } else {
            text.to_string()
        };
        info!("[发送] {}", preview);
    }
    
    tokio::time::sleep(tokio::time::Duration::from_millis(100)).await;
    
    if let Some(original) = original_clipboard {
        if let Err(e) = copy_to_clipboard(&original) {
            error!("[-] 还原剪贴板失败: {}", e);
        }
    }
}

fn copy_to_clipboard(text: &str) -> Result<(), String> {
    let mut clipboard = Clipboard::new().map_err(|e| e.to_string())?;
    clipboard.set_text(text).map_err(|e| e.to_string())?;
    Ok(())
}

fn get_clipboard_text() -> Option<String> {
    let mut clipboard = Clipboard::new().ok()?;
    clipboard.get_text().ok()
}
