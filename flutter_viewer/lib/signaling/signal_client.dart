import 'dart:async';
import 'dart:convert';

import 'package:web_socket_channel/web_socket_channel.dart';

import '../config.dart';

/// ScreenShare 信令客户端，严格对齐 server.js 的 JSON over WebSocket 协议。
///
/// 路径固定为 /ws，心跳 10s 发一次 ping，服务端 45s 超时清房。
class SignalClient {
  SignalClient({required this.listener});

  /// 可在页面内叠加/替换监听器；join 后 UI 需要订阅 relay/joined 等事件。
  SignalListener listener;

  WebSocketChannel? _channel;
  StreamSubscription? _sub;
  Timer? _heartbeat;

  bool get isConnected => _channel != null;

  /// 连接信令服务器，失败会回调 [SignalListener.onError]。
  Future<void> connect() async {
    if (!ScreenShareConfig.hasSignalServer) {
      listener.onError('尚未配置信令服务器地址');
      return;
    }

    try {
      final channel = WebSocketChannel.connect(
        Uri.parse(ScreenShareConfig.signalUrl),
      );

      _channel = channel;
      _sub = channel.stream.listen(
        _onMessage,
        onError: (Object e) {
          listener.onError('连接异常：$e');
          _cleanup();
        },
        onDone: () {
          listener.onDisconnected();
          _cleanup();
        },
      );

      _startHeartbeat();
    } catch (e) {
      listener.onError('连接失败：$e');
      _cleanup();
    }
  }

  void _onMessage(dynamic raw) {
    try {
      final json = jsonDecode(raw as String) as Map<String, dynamic>;
      _handle(json);
    } catch (_) {
      listener.onError('无效的消息格式');
    }
  }

  void _handle(Map<String, dynamic> msg) {
    switch (msg['type']) {
      case 'created':
        listener.onCreated(
          code: msg['code'] as String? ?? '',
          token: msg['token'] as String? ?? '',
        );
        break;

      case 'join-pending':
        listener.onJoinPending();
        break;

      case 'join-request':
        listener.onJoinRequest(msg['viewerId'] as int? ?? 0);
        break;

      case 'joined':
        listener.onJoined(
          code: msg['code'] as String? ?? '',
          viewerId: msg['viewerId'] as int? ?? 0,
        );
        break;

      case 'join-rejected':
        listener.onJoinRejected();
        break;

      case 'join-cancelled':
        listener.onJoinCancelled(msg['viewerId'] as int? ?? 0);
        break;

      case 'peer-ready':
        listener.onPeerReady();
        break;

      case 'viewer-joined':
        listener.onViewerJoined(msg['viewerId'] as int? ?? 0);
        break;

      case 'viewer-left':
        listener.onViewerLeft(msg['viewerId'] as int? ?? 0);
        break;

      case 'host-left':
        listener.onHostLeft();
        break;

      case 'come-on':
        listener.onComeOn();
        break;

      case 'relay':
        listener.onRelay(
          data: msg['data'] as String? ?? '',
          viewerId: msg['viewerId'] as int? ?? 0,
        );
        break;

      case 'pong':
        // 心跳回包，无需 UI 动作
        break;

      case 'error':
        listener.onError(msg['message'] as String? ?? '未知错误');
        break;

      default:
        listener.onError('未知消息类型：${msg['type']}');
    }
  }

  void _startHeartbeat() {
    _heartbeat?.cancel();
    _heartbeat = Timer.periodic(
      ScreenShareConfig.heartbeatInterval,
      (_) => _send({'type': 'ping'}),
    );
  }

  void _send(Map<String, dynamic> msg) {
    try {
      _channel?.sink.add(jsonEncode(msg));
    } catch (_) {
      listener.onError('消息发送失败');
    }
  }

  /// 创建会议（host 端）。
  void createMeeting(String code) {
    _send({'type': 'create', 'code': code.toUpperCase()});
  }

  /// 请求加入会议（viewer 端）。
  void joinMeeting(String code) {
    _send({'type': 'join', 'code': code.toUpperCase()});
  }

  /// host 同意某个 viewer 加入。
  void acceptViewer(int viewerId) {
    _send({'type': 'accept', 'viewerId': viewerId});
  }

  /// host 拒绝某个 viewer 加入。
  void rejectViewer(int viewerId) {
    _send({'type': 'reject', 'viewerId': viewerId});
  }

  /// 转发 SDP / ICE，viewerId 仅在 host 发往指定 viewer 时使用。
  void relay(String data, {int viewerId = 0}) {
    _send({
      'type': 'relay',
      'data': data,
      if (viewerId != 0) 'viewerId': viewerId,
    });
  }

  void plsJoin(String code) {
    _send({'type': 'pls-join', 'code': code.toUpperCase()});
  }

  void dispose() {
    _heartbeat?.cancel();
    _sub?.cancel();
    _channel?.sink.close();
    _channel = null;
    _sub = null;
    _heartbeat = null;
  }

  void _cleanup() {
    _heartbeat?.cancel();
    _sub?.cancel();
    _channel = null;
    _sub = null;
    _heartbeat = null;
  }
}

/// 信令事件回调，对应 server.js 的 server -> client 消息。
abstract class SignalListener {
  void onCreated({required String code, required String token}) {}
  void onJoinPending() {}
  void onJoinRequest(int viewerId) {}
  void onJoined({required String code, required int viewerId}) {}
  void onJoinRejected() {}
  void onJoinCancelled(int viewerId) {}
  void onPeerReady() {}
  void onViewerJoined(int viewerId) {}
  void onViewerLeft(int viewerId) {}
  void onHostLeft() {}
  void onComeOn() {}
  void onRelay({required String data, required int viewerId}) {}
  void onError(String message) {}
  void onDisconnected() {}
}
