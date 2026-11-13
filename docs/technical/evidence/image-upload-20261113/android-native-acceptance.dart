import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:integration_test/integration_test.dart';
import 'package:merchant_app/features/goods/goods_image_preview.dart';
import 'package:neargo_platform/neargo_platform.dart';

void main() {
  IntegrationTestWidgetsFlutterBinding.ensureInitialized();
  testWidgets('Android system gallery selects real PNG and both 166 origins render', (tester) async {
    await tester.pumpWidget(const MaterialApp(home: Scaffold(body: Text('Image upload acceptance'))));
    await tester.pumpAndSettle();
    debugPrint('NATIVE_IMAGE_PICKER_READY');
    final picked = await ImagePickerCameraAdapter.gallery().capture();
    expect(picked.isSuccess, isTrue, reason: 'Native gallery must return selected image bytes');
    final bytes = picked.requireValue;
    expect(bytes.length, 69);
    debugPrint('NATIVE_IMAGE_PICKER_SELECTED');
    const reference = '/uploads/M26110512GRAR2551/ST261105S2K8ZD6T3C/goods/202611/6466fb8413474351bb0aba562247b02d.png';
    for (final origin in ['nearshop.umeshop.ai', 'twpay.umeshop.ai']) {
      await tester.pumpWidget(MaterialApp(home: Scaffold(body: Column(children: [
        GoodsImagePreview(reference: 'local-selected.png', bytes: bytes),
        GoodsImagePreview(reference: reference, baseUrl: 'https://$origin/shop-api', allowNetwork: true),
      ]))));
      for (var i = 0; i < 60; i++) {
        await tester.pump(const Duration(milliseconds: 500));
        final images = tester.widgetList<RawImage>(find.byType(RawImage)).toList();
        if (images.length == 2 && images.every((image) => image.image != null)) break;
      }
      final images = tester.widgetList<RawImage>(find.byType(RawImage)).toList();
      expect(images, hasLength(2));
      for (final image in images) {
        expect(image.image, isNotNull);
        expect(image.image!.width, 1); expect(image.image!.height, 1);
      }
      expect(find.byIcon(Icons.broken_image_outlined), findsNothing);
    }
    await tester.pumpWidget(const SizedBox.shrink());
    debugPrint('NATIVE_IMAGE_ACCEPTANCE_PASSED');
  }, timeout: const Timeout(Duration(minutes: 3)));
}
