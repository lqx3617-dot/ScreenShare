import 'package:flutter/material.dart';
import 'package:flutter_webrtc/flutter_webrtc.dart';

import 'config.dart';
import 'signaling/signal_client.dart';
import 'webrtc/peer_manager.dart';

void main() {
  runApp(const ScreenShareApp());
}

class ScreenShareApp extends StatelessWidget {
  const ScreenShareApp({super.key});

  @override
  Widget build(BuildContext context) {
    return MaterialApp(
      title: '共享屏界',
      theme: ThemeData(
        colorSchemeSeed: const Color(0xFFE6396B),
        useMaterial3: true,
      ),
      home: const JoinScreen(),
    );
  }
}

/// 加入会议页：输入 4 位会议号，连上信令服务器并等待 host 确认。
class JoinScreen extends StatefulWidget {
  const JoinScreen({super.key});

  @override
  State<JoinScreen> createState() => _JoinScreenState();
}

class _JoinScreenState extends State<JoinScreen> {
  final TextEditingController _codeController = TextEditingController();
  bool _joining = false;
  String? _error;

  @override
  void dispose() {
    _codeController.dispose();
    super.dispose();
  }

  Future<void> _join() async {
    final code = _codeController.text.trim();
    if (code.length != ScreenShareConfig.meetingCodeLength ||
        int.tryParse(code) == null) {
      setState(() => _error = '会议号需为 4 位数字');
      return;
    }

    setState(() {
      _joining = true;
      _error = null;
    });

    final signal = SignalClient(listener: _SignalListener());
    final peer = PeerManager(signal: signal);
    await peer.init();

    if (!mounted) {
      await peer.dispose();
      signal.dispose();
      return;
    }

    Navigator.of(context).push(
      MaterialPageRoute<void>(
        builder: (_) => WatchScreen(
          code: code,
          signal: signal,
          peer: peer,
        ),
      ),
    );

    signal.connect();
    signal.joinMeeting(code);

    setState(() => _joining = false);
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(title: const Text('加入会议')),
      body: Padding(
        padding: const EdgeInsets.all(24),
        child: Column(
          mainAxisAlignment: MainAxisAlignment.center,
          crossAxisAlignment: CrossAxisAlignment.stretch,
          children: [
            const Text(
              '共享屏界',
              textAlign: TextAlign.center,
              style: TextStyle(fontSize: 32, fontWeight: FontWeight.bold),
            ),
            const SizedBox(height: 24),
            TextField(
              controller: _codeController,
              keyboardType: TextInputType.number,
              maxLength: ScreenShareConfig.meetingCodeLength,
              textAlign: TextAlign.center,
              style: const TextStyle(fontSize: 28, letterSpacing: 8),
              decoration: const InputDecoration(
                hintText: '0000',
                counterText: '',
              ),
            ),
            if (_error != null)
              Padding(
                padding: const EdgeInsets.only(top: 8),
                child: Text(
                  _error!,
                  textAlign: TextAlign.center,
                  style: TextStyle(color: Theme.of(context).colorScheme.error),
                ),
              ),
            const SizedBox(height: 24),
            FilledButton(
              onPressed: _joining ? null : _join,
              style: FilledButton.styleFrom(
                padding: const EdgeInsets.symmetric(vertical: 16),
              ),
              child: Text(_joining ? '加入中...' : '加入会议'),
            ),
            const SizedBox(height: 12),
            Text(
              ScreenShareConfig.hasSignalServer
                  ? '信令服务器：${ScreenShareConfig.signalUrl}'
                  : '尚未配置信令服务器（--dart-define=SIGNAL_URL=...）',
              textAlign: TextAlign.center,
              style: Theme.of(context).textTheme.bodySmall,
            ),
          ],
        ),
      ),
    );
  }
}

class _SignalListener extends SignalListener {
  @override
  void onError(String message) {}
}

/// 观看页：显示 host 共享的屏幕画面，并处理信令事件。
class WatchScreen extends StatefulWidget {
  const WatchScreen({
    super.key,
    required this.code,
    required this.signal,
    required this.peer,
  });

  final String code;
  final SignalClient signal;
  final PeerManager peer;

  @override
  State<WatchScreen> createState() => _WatchScreenState();
}

class _WatchScreenState extends State<WatchScreen> {
  bool _joined = false;
  String _status = '等待 host 确认...';

  @override
  void initState() {
    super.initState();
    final signal = widget.signal;
    // 在已有 listener 上叠加回调：收到 relay 交给 peer，收到 joined 更新状态。
    signal.listener = _CombinedListener(
      base: signal.listener,
      onJoined: () => setState(() {
        _joined = true;
        _status = '已连接，等待画面...';
      }),
      onRelay: (data, viewerId) => widget.peer.handleRelay(data),
      onHostLeft: () => setState(() => _status = '共享方已离开'),
      onError: (msg) => setState(() => _status = msg),
    );
  }

  @override
  void dispose() {
    widget.signal.dispose();
    widget.peer.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(
        title: Text('会议 ${widget.code}'),
        actions: [
          Center(
            child: Padding(
              padding: const EdgeInsets.only(right: 16),
              child: Text(_status),
            ),
          ),
        ],
      ),
      body: widget.peer.remoteRenderer == null
          ? const Center(child: CircularProgressIndicator())
          : RTCVideoView(
              widget.peer.remoteRenderer!,
              mirror: false,
            ),
    );
  }
}

class _CombinedListener extends SignalListener {
  _CombinedListener({
    required this.base,
    required this.onJoined,
    required this.onRelay,
    required this.onHostLeft,
    required this.onError,
  });

  final SignalListener base;
  final VoidCallback onJoined;
  final void Function(String data, int viewerId) onRelay;
  final VoidCallback onHostLeft;
  final void Function(String message) onError;

  @override
  void onJoined({required String code, required int viewerId}) => onJoined();

  @override
  void onRelay({required String data, required int viewerId}) =>
      onRelay(data, viewerId);

  @override
  void onHostLeft() => onHostLeft();

  @override
  void onError(String message) => onError(message);

  @override
  void onCreated({required String code, required String token}) {}
  @override
  void onJoinPending() {}
  @override
  void onJoinRequest(int viewerId) {}
  @override
  void onJoinRejected() {}
  @override
  void onJoinCancelled(int viewerId) {}
  @override
  void onPeerReady() {}
  @override
  void onViewerJoined(int viewerId) {}
  @override
  void onViewerLeft(int viewerId) {}
  @override
  void onComeOn() {}
  @override
  void onDisconnected() {}
}
