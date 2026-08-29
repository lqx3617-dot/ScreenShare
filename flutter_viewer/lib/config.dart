/// ScreenShare iOS Flutter 观看端配置。
///
/// 与服务端 server.js 和安卓 SignalClient 保持同一套协议：
/// - WebSocket 路径 /ws
/// - 心跳 interval 10s（服务端 45s 超时，10s 发一次 ping 足够）
/// - 会议号 4 位数字
///
/// SIGNAL_URL 为空时表示尚未部署/未配置服务器，UI 会提示填写。
class ScreenShareConfig {
  ScreenShareConfig._();

  /// 信令服务器地址，例如：
  ///   ws://192.168.1.10:8080/ws        // 局域网联调
  ///   wss://share.example.com/ws       // 公网反代部署
  static const String signalUrl = String.fromEnvironment(
    'SIGNAL_URL',
    defaultValue: '',
  );

  /// 心跳间隔，与服务端 HEARTBEAT_TIMEOUT=45s 配套。
  static const Duration heartbeatInterval = Duration(seconds: 10);

  /// 会议号长度，服务端只接受 4 位数字。
  static const int meetingCodeLength = 4;

  /// STUN 服务器，与安卓 WebRTCPeer.STUN_URLS 保持一致。
  static const List<String> stunUrls = [
    'stun:stun.l.google.com:19302',
    'stun:stun1.l.google.com:19302',
    'stun:stun.cloudflare.com:3478',
  ];

  static bool get hasSignalServer => signalUrl.isNotEmpty;
}
