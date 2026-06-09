import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:opencray/core/design/opencray_motion.dart';

void main() {
  test('spatial page curve starts moving immediately and lands softly', () {
    final double earlyProgress = OpenCrayMotion.spatial.transform(0.05);
    final double lateRemaining = 1 - OpenCrayMotion.spatial.transform(0.95);

    expect(earlyProgress, greaterThan(0.05));
    expect(earlyProgress, lessThan(0.25));
    expect(lateRemaining, lessThan(0.04));
  });

  test('spatial exit curve avoids sticky reverse route starts', () {
    final double earlyExitProgress = OpenCrayMotion.spatialExit.transform(0.05);

    expect(earlyExitProgress, greaterThan(0.05));
    expect(earlyExitProgress, lessThan(0.25));
  });

  testWidgets(
    'directional indexed stack paints the incoming tab above the outgoing tab',
    (tester) async {
      int selectedIndex = 1;
      late StateSetter setHarnessState;

      await tester.pumpWidget(
        MaterialApp(
          home: StatefulBuilder(
            builder: (context, setState) {
              setHarnessState = setState;
              return SizedBox(
                width: 320,
                height: 240,
                child: OpenCrayDirectionalIndexedStack(
                  index: selectedIndex,
                  children: const <Widget>[
                    ColoredBox(
                      key: ValueKey<String>('motion-page-chat'),
                      color: Colors.red,
                    ),
                    ColoredBox(
                      key: ValueKey<String>('motion-page-skills'),
                      color: Colors.blue,
                    ),
                    ColoredBox(
                      key: ValueKey<String>('motion-page-files'),
                      color: Colors.green,
                    ),
                  ],
                ),
              );
            },
          ),
        ),
      );

      setHarnessState(() {
        selectedIndex = 0;
      });
      await tester.pump();

      expect(
        _indexedStackLayerKeys(tester).last,
        'opencray-indexed-stack-layer-0',
      );

      await tester.pumpAndSettle();
      setHarnessState(() {
        selectedIndex = 1;
      });
      await tester.pump();

      expect(
        _indexedStackLayerKeys(tester).last,
        'opencray-indexed-stack-layer-1',
      );
    },
  );
}

List<String> _indexedStackLayerKeys(WidgetTester tester) {
  final Stack stack = tester.widget<Stack>(
    find.byKey(const ValueKey<String>('opencray-indexed-stack-paint-stack')),
  );
  return stack.children
      .map((Widget child) => child.key)
      .whereType<ValueKey<String>>()
      .map((ValueKey<String> key) => key.value)
      .toList(growable: false);
}
