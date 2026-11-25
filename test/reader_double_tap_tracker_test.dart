import 'dart:ui';

import 'package:flutter_test/flutter_test.dart';
import 'package:flureadium/src/reader/reader_double_tap_tracker.dart';

void main() {
  const surfaceSize = Size(400, 800);
  const center = Offset(200, 400);

  testWidgets('one tap remains a single tap', (tester) async {
    final tracker = ReaderDoubleTapTracker();
    var singleTaps = 0;
    var doubleTaps = 0;

    tracker.registerTap(
      position: center,
      surfaceSize: surfaceSize,
      onSingleTap: () => singleTaps++,
      onDoubleTap: () => doubleTaps++,
    );
    await tester.pump(const Duration(milliseconds: 301));

    expect(singleTaps, 1);
    expect(doubleTaps, 0);
  });

  testWidgets('two nearby taps invoke only double tap', (tester) async {
    final tracker = ReaderDoubleTapTracker();
    var singleTaps = 0;
    var doubleTaps = 0;

    void tap(Offset position) => tracker.registerTap(
      position: position,
      surfaceSize: surfaceSize,
      onSingleTap: () => singleTaps++,
      onDoubleTap: () => doubleTaps++,
    );

    tap(center);
    await tester.pump(const Duration(milliseconds: 100));
    tap(const Offset(214, 406));
    await tester.pump(const Duration(milliseconds: 301));

    expect(singleTaps, 0);
    expect(doubleTaps, 1);
  });

  testWidgets('a second tap pair invokes the toggle again', (tester) async {
    final tracker = ReaderDoubleTapTracker();
    var doubleTaps = 0;

    void tap() => tracker.registerTap(
      position: center,
      surfaceSize: surfaceSize,
      onSingleTap: null,
      onDoubleTap: () => doubleTaps++,
    );

    tap();
    await tester.pump(const Duration(milliseconds: 100));
    tap();
    await tester.pump(const Duration(milliseconds: 100));
    tap();
    await tester.pump(const Duration(milliseconds: 100));
    tap();

    expect(doubleTaps, 2);
  });

  testWidgets('two distant taps are not a double tap', (tester) async {
    final tracker = ReaderDoubleTapTracker();
    var singleTaps = 0;
    var doubleTaps = 0;

    void tap(Offset position) => tracker.registerTap(
      position: position,
      surfaceSize: surfaceSize,
      onSingleTap: () => singleTaps++,
      onDoubleTap: () => doubleTaps++,
    );

    tap(const Offset(120, 400));
    await tester.pump(const Duration(milliseconds: 100));
    tap(const Offset(280, 400));
    await tester.pump(const Duration(milliseconds: 301));

    expect(singleTaps, 2);
    expect(doubleTaps, 0);
  });

  testWidgets('edge taps never trigger fullscreen double tap', (tester) async {
    final tracker = ReaderDoubleTapTracker();
    var singleTaps = 0;
    var doubleTaps = 0;

    for (var index = 0; index < 2; index++) {
      tracker.registerTap(
        position: const Offset(20, 400),
        surfaceSize: surfaceSize,
        onSingleTap: () => singleTaps++,
        onDoubleTap: () => doubleTaps++,
      );
      await tester.pump(const Duration(milliseconds: 100));
    }

    expect(singleTaps, 2);
    expect(doubleTaps, 0);
  });
}
