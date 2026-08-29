import 'package:flutter_test/flutter_test.dart';
import 'package:screen_share_flutter/config.dart';

void main() {
  group('ScreenShareConfig 冒烟测试', () {
    test('会议号长度必须为 4 位，与服务端 isValidCode 一致', () {
      expect(ScreenShareConfig.meetingCodeLength, 4);
    });

    test('心跳间隔必须为 10 秒，低于服务端 45 秒超时', () {
      expect(
        ScreenShareConfig.heartbeatInterval,
        const Duration(seconds: 10),
      );
    });

    test('STUN 服务器与安卓 WebRTCPeer.STUN_URLS 保持一致', () {
      expect(
        ScreenShareConfig.stunUrls,
        contains('stun:stun.l.google.com:19302'),
      );
      expect(
        ScreenShareConfig.stunUrls,
        contains('stun:stun.cloudflare.com:3478'),
      );
      expect(ScreenShareConfig.stunUrls.length, greaterThanOrEqualTo(3));
    });

    test('未配置 SIGNAL_URL 时 hasSignalServer 为 false', () {
      expect(ScreenShareConfig.hasSignalServer, isFalse);
    });
  });
}
