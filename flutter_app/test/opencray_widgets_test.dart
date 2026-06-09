import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:opencray/core/design/opencray_widgets.dart';

void main() {
  testWidgets('OpenCrayStateCard renders compact busy and recovery content', (
    tester,
  ) async {
    await tester.pumpWidget(
      MaterialApp(
        home: Scaffold(
          body: OpenCrayStateCard(
            title: 'Unable to load',
            body: 'Network request failed.',
            tone: OpenCrayStateTone.danger,
            leadingIcon: Icons.error_outline,
            isLoading: true,
            action: TextButton(onPressed: () {}, child: const Text('Retry')),
          ),
        ),
      ),
    );

    expect(find.text('Unable to load'), findsOneWidget);
    expect(find.text('Network request failed.'), findsOneWidget);
    expect(find.text('Retry'), findsOneWidget);
    expect(find.byType(CircularProgressIndicator), findsOneWidget);
  });
}
