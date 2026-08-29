import 'dart:convert';

import 'package:flutter_webrtc/flutter_webrtc.dart';

import '../config.dart';
import '../signaling/signal_client.dart';

/// iOS 观看端 WebRTC 管理。
///
/// 协议对齐安卓 SignalManager.kt：
/// - relay 载荷：{"sdp":{"type":"offer"|"answer","sdp":"..."},"ice":[...]}
/// - 增量候选：{"type":"candidate","id":...,"label":...,"candidate":"..."}
class PeerManager {
  PeerManager({required this.signal});

  final SignalClient signal;

  RTCPeerConnection? _pc;
  MediaStream? _remoteStream;
  RTCVideoRenderer? _remoteRenderer;

  RTCVideoRenderer? get remoteRenderer => _remoteRenderer;

  Future<void> init() async {
    _remoteRenderer = RTCVideoRenderer();
    await _remoteRenderer?.initialize();

    final config = <String, dynamic>{
      'iceServers': [
        for (final url in ScreenShareConfig.stunUrls) {'urls': url},
      ],
    };

    _pc = await createPeerConnection(config);

    _pc?.onTrack = (RTCTrackEvent event) {
      final stream = event.streams.firstOrNull;
      if (stream != null) {
        _remoteStream = stream;
        _remoteRenderer?.srcObject = stream;
      }
    };

    _pc?.onIceCandidate = (RTCIceCandidate candidate) {
      final data = jsonEncode({
        'type': 'candidate',
        'id': candidate.sdpMid,
        'label': candidate.sdpMLineIndex,
        'candidate': candidate.candidate,
      });
      signal.relay(data);
    };
  }

  /// 收到 host 的 Offer，创建 Answer 并回传。
  Future<void> handleRemoteSdp(Map<String, dynamic> sdpMap) async {
    final pc = _pc;
    if (pc == null) return;

    final type = sdpMap['type'] as String?;
    final sdp = sdpMap['sdp'] as String?;
    if (type != 'offer' || sdp == null) return;

    final description = RTCSessionDescription(sdp, 'offer');
    await pc.setRemoteDescription(description);

    final answer = await pc.createAnswer();
    await pc.setLocalDescription(answer);

    final answerData = jsonEncode({
      'sdp': {
        'type': 'answer',
        'sdp': answer.sdp,
      },
      'ice': <dynamic>[],
    });
    signal.relay(answerData);
  }

  /// 收到 host 的增量 ICE 候选。
  Future<void> handleIceCandidate(Map<String, dynamic> candidateMap) async {
    final pc = _pc;
    if (pc == null) return;

    final id = candidateMap['id'] as String?;
    final label = candidateMap['label'] as int?;
    final candidate = candidateMap['candidate'] as String?;
    if (id == null || label == null || candidate == null) return;

    await pc.addCandidate(RTCIceCandidate(candidate, id, label));
  }

  /// 收到 host 通过 relay 发来的完整载荷。
  Future<void> handleRelay(String rawData) async {
    try {
      final json = jsonDecode(rawData) as Map<String, dynamic>;

      final sdp = json['sdp'] as Map<String, dynamic>?;
      if (sdp != null) {
        await handleRemoteSdp(sdp);
      }

      final ice = json['ice'] as List<dynamic>?;
      if (ice != null) {
        for (final candidate in ice) {
          if (candidate is Map<String, dynamic>) {
            await handleIceCandidate(candidate);
          }
        }
      }

      // 单独增量候选：{"type":"candidate",...}
      if (json['type'] == 'candidate') {
        await handleIceCandidate(json);
      }
    } catch (_) {
      // 忽略无法解析的 relay 载荷
    }
  }

  Future<void> dispose() async {
    await _pc?.close();
    _pc = null;
    _remoteStream = null;
    await _remoteRenderer?.dispose();
    _remoteRenderer = null;
  }
}
