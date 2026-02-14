/**
 * VoiceSync - 电脑端 (含服务器)
 * 运行: node index.js 或 bun run index.js
 */

const { WebSocketServer } = require('ws');
const { keyboard, clipboard, Key } = require('@nut-tree-fork/nut-js');

const PORT = 8765;
let mobileClient = null;

const wss = new WebSocketServer({ port: PORT });
console.log(`[服务器] 启动在端口 ${PORT}`);

wss.on('connection', (ws) => {
    console.log('[服务器] 手机端连接');
    
    ws.on('message', (data) => {
        try {
            const msg = JSON.parse(data.toString());
            if (msg.type === 'register' && msg.device === 'mobile') {
                mobileClient = ws;
                ws.send(JSON.stringify({ type: 'registered' }));
                console.log('[服务器] 手机端注册成功');
            } else if (msg.type === 'text' && msg.content) {
                console.log(`[收到] ${msg.content}`);
                typeText(msg.content);
            }
        } catch (e) { console.error(e); }
    });

    ws.on('close', () => { mobileClient = null; });
});

async function typeText(text) {
    try {
        await clipboard.setContent(text);
        await new Promise(r => setTimeout(r, 50));
        await keyboard.pressKey(Key.LeftControl, Key.V);
        await keyboard.releaseKey(Key.LeftControl, Key.V);
        console.log(`[发送] ${text.substring(0, 15)}...`);
    } catch (e) {
        console.error('[错误]', e.message);
    }
}

console.log('');
console.log('═══════════════════════════════════════');
console.log('       VoiceSync - 语音同步');
console.log('═══════════════════════════════════════');
console.log(`端口: ${PORT}  |  状态: 运行中`);
console.log('═══════════════════════════════════════');
console.log('手机输入: ws://192.168.1.x:8765');
console.log('═══════════════════════════════════════');
