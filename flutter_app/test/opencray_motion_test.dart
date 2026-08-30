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

  testWidgets('list entrance arrives as a wave and settles at rest', (
    tester,
  ) async {
    await tester.pumpWidget(_entranceHarness(enabled: true));
    await tester.pump(const Duration(milliseconds: 60));

    final double firstRow = _rowOpacity(tester, 0);
    final double lastRow = _rowOpacity(tester, 3);
    expect(firstRow, greaterThan(lastRow));
    expect(lastRow, lessThan(1));

    await tester.pumpAndSettle();
    for (int index = 0; index < 4; index += 1) {
      expect(_rowOpacity(tester, index), 1);
    }
  });

  testWidgets('list entrance is skipped when disabled or motion is reduced', (
    tester,
  ) async {
    await tester.pumpWidget(_entranceHarness(enabled: false));
    await tester.pump();
    expect(find.byType(Opacity), findsNothing);

    await tester.pumpWidget(
      MediaQuery(
        data: const MediaQueryData(disableAnimations: true),
        child: _entranceHarness(enabled: true),
      ),
    );
    await tester.pump();
    for (int index = 0; index < 4; index += 1) {
      expect(_rowOpacity(tester, index), 1);
    }
  });

  test('list entrance window closes on its own', () {
    final window = OpenCrayListEntranceWindow();
    expect(window.isActive, isFalse);

    window.restart();
    expect(window.isActive, isTrue);
  });
}

Widget _entranceHarness({required bool enabled}) {
  return MaterialApp(
    home: Column(
      children: <Widget>[
        for (int index = 0; index < 4; index += 1)
          OpenCrayListEntrance(
            index: index,
            enabled: enabled,
            child: SizedBox(
              key: ValueKey<String>('entrance-row-$index'),
              height: 20,
            ),
          ),
      ],
    ),
  );
}

double _rowOpacity(WidgetTester tester, int index) {
  final Finder row = find.byKey(ValueKey<String>('entrance-row-$index'));
  return tester
      .widgetList<Opacity>(
        find.ancestor(of: row, matching: find.byType(Opacity)),
      )
      .first
      .opacity;
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
