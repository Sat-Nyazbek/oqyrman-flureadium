import 'dart:async';
import 'dart:ui';

/// Recognizes a deliberate double tap without joining the platform-view
/// gesture arena.
///
/// Reader platform views consume their own swipes and edge taps, so the usual
/// [GestureDetector] can interfere with page navigation. This tracker is fed by
/// the raw pointer listener that already surrounds the native reader.
class ReaderDoubleTapTracker {
  ReaderDoubleTapTracker({
    this.timeout = const Duration(milliseconds: 300),
    this.maxDistance = 48,
    this.horizontalInsetFraction = 0.15,
  });

  final Duration timeout;
  final double maxDistance;
  final double horizontalInsetFraction;

  Timer? _pendingTimer;
  Offset? _pendingPosition;
  VoidCallback? _pendingSingleTap;

  void registerTap({
    required Offset position,
    required Size surfaceSize,
    required VoidCallback? onSingleTap,
    required VoidCallback? onDoubleTap,
  }) {
    if (onDoubleTap == null || !_isInDoubleTapZone(position, surfaceSize)) {
      _flushPendingSingleTap();
      onSingleTap?.call();
      return;
    }

    final previousPosition = _pendingPosition;
    if ((_pendingTimer?.isActive ?? false) &&
        previousPosition != null &&
        (position - previousPosition).distance <= maxDistance) {
      _clearPendingTap();
      onDoubleTap();
      return;
    }

    _flushPendingSingleTap();
    _pendingPosition = position;
    _pendingSingleTap = onSingleTap;
    _pendingTimer = Timer(timeout, () {
      final callback = _pendingSingleTap;
      _clearPendingTap();
      callback?.call();
    });
  }

  void cancel() => _clearPendingTap();

  bool _isInDoubleTapZone(Offset position, Size surfaceSize) {
    if (surfaceSize.width <= 0) return true;
    final inset = surfaceSize.width * horizontalInsetFraction;
    return position.dx >= inset && position.dx <= surfaceSize.width - inset;
  }

  void _flushPendingSingleTap() {
    if (!(_pendingTimer?.isActive ?? false)) {
      _clearPendingTap();
      return;
    }
    final callback = _pendingSingleTap;
    _clearPendingTap();
    callback?.call();
  }

  void _clearPendingTap() {
    _pendingTimer?.cancel();
    _pendingTimer = null;
    _pendingPosition = null;
    _pendingSingleTap = null;
  }
}
