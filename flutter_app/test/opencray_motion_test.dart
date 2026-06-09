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
    final double earlyExitDistance =
        1 - OpenCrayMotion.spatialExit.transform(0.95);

    expect(earlyExitDistance, greaterThan(0.08));
  });
}
