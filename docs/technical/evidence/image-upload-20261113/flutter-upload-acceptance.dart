import 'dart:async';
import 'dart:convert';
import 'dart:io';
import 'package:dio/dio.dart';
import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:merchant_app/features/goods/goods_image_preview.dart';
import 'package:merchant_app/features/standard_onboarding/onboarding_api.dart';
import 'package:merchant_app/features/standard_onboarding/onboarding_models.dart';
import 'package:neargo_foundation/neargo_foundation.dart';

class MemoryTokens implements TokenStore {
  SessionTokens? value;
  @override Future<SessionTokens?> read() async => value;
  @override Future<void> write(SessionTokens tokens) async { value = tokens; }
  @override Future<void> clear() async { value = null; }
}

void main() {
  final configPath = Platform.environment['SHOP_UPLOAD_ACCEPTANCE_CONFIG'] ?? '';
  testWidgets('real App multipart upload and Flutter image rendering on both 166 domains', (tester) async {
    final previous = HttpOverrides.current;
    HttpOverrides.global = null;
    final imageClient = HttpClient();
    debugNetworkImageHttpClientProvider = () => imageClient;
    addTearDown(() {
      debugPrint("IMAGE_ACCEPTANCE_TEARDOWN");
      imageClient.close(force: true);
      debugNetworkImageHttpClientProvider = null;
      HttpOverrides.global = previous;
      debugPrint("IMAGE_ACCEPTANCE_CLEANED");
    });
    final config = jsonDecode(File(configPath).readAsStringSync()) as Map<String, dynamic>;
    final transport = Dio();
    addTearDown(() { transport.close(force: true); });
    final session = SessionManager(store: MemoryTokens(), refreshTokens: (_) => throw StateError('Refresh is not part of this acceptance'));
    final bytes = base64Decode(config['fixturePng'] as String);
    final urls = await tester.runAsync(() async {
      await session.signIn(SessionTokens(accessToken: config['token'] as String, refreshToken: ''));
      final api = OnboardingApi(environment: 'live-dev', client: ApiClient(
        config: ApiConfig(baseUrl: config['apiBaseUrl'] as String, production: true),
        session: session, transport: transport,
      ));
      final selection = OnboardingSelection(storeNo: config['storeNo'] as String,
        entityNo: config['entityNo'] as String, name: 'Upload acceptance', canManage: true, market: 'TW');
      // Match the production goods page: same API client, scope headers and GOODS purpose.
      final result = await api.upload(selection, bytes, 'upload-acceptance.png', bizType: 'GOODS');
      expect(result.isSuccess, isTrue, reason: 'App multipart request must succeed');
      final reference = result.requireValue['url'] as String;
      expect(reference.startsWith('/uploads/'), isTrue);
      final urls = <String>[];
      for (final host in ['nearshop.umeshop.ai', 'twpay.umeshop.ai']) {
        final url = goodsImageUri(reference, 'https://$host/shop-api')!;
        expect(url.path.startsWith('/shop-api/'), isFalse);
        final http = HttpClient();
        try {
          final response = await (await http.getUrl(url)).close();
          expect(response.statusCode, 200);
          expect(await response.fold<List<int>>([], (all, next) => all..addAll(next)), bytes);
        } finally { http.close(force: true); }
        final decoded = Completer<ImageInfo>();
        final stream = NetworkImage(url.toString()).resolve(ImageConfiguration.empty);
        final listener = ImageStreamListener((info, _) { if (!decoded.isCompleted) decoded.complete(info); },
          onError: (Object error, StackTrace? stack) { if (!decoded.isCompleted) decoded.completeError(StateError('Remote image decoding failed')); });
        stream.addListener(listener);
        try {
          final info = await decoded.future.timeout(const Duration(seconds: 30));
          expect(info.image.width, 1); expect(info.image.height, 1);
        } finally { stream.removeListener(listener); }
        urls.add(url.toString());
      }
      return urls;
    });
    expect(urls, hasLength(2));
    for (final url in urls!) {
      await tester.pumpWidget(MaterialApp(home: Scaffold(body: GoodsImagePreview(
        reference: url, allowNetwork: true, baseUrl: config['apiBaseUrl'] as String))));
      await tester.pumpAndSettle();
      expect(find.byIcon(Icons.broken_image_outlined), findsNothing);
      expect(tester.widget<RawImage>(find.byType(RawImage)).image, isNotNull);
    }
    await tester.pumpWidget(const SizedBox.shrink());
    await tester.runAsync(() async { await session.logout(); transport.close(force: true); });
    debugNetworkImageHttpClientProvider = null;
    imageClient.close(force: true);
    debugPrint("IMAGE_ACCEPTANCE_BODY_DONE");
    File(config['resultFile'] as String).writeAsStringSync(jsonEncode({
      'appMultipartUpload': true, 'bothDomainsByteMatched': true,
      'bothDomainsFlutterDecoded': true, 'goodsPreviewRendered': true,
      'fixtureDimensions': '1x1', 'nativePickerExercised': false,
      'businessRecordChanged': false,
    }));
  }, skip: configPath.isEmpty, timeout: const Timeout(Duration(minutes: 2)));
}
