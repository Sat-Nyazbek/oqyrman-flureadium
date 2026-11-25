import 'dart:async';
import 'dart:convert';
import 'dart:io';

import 'package:collection/collection.dart';
import 'package:flutter/material.dart';
import 'package:flutter/rendering.dart';
import 'package:flutter/services.dart';
import 'package:flureadium_platform_interface/flureadium_platform_interface.dart';
import 'package:rxdart/rxdart.dart';

import 'reader_channel.dart';
import 'src/reader/orientation_handler_mixin.dart';
import 'src/reader/reader_double_tap_tracker.dart';
import 'src/reader/reader_lifecycle_mixin.dart';
import 'src/reader/wakelock_manager_mixin.dart';
import 'src/utils/navigation_helper.dart';
import 'src/utils/toc_matcher.dart';

const _viewType = 'dev.mulev.flureadium/ReadiumReaderWidget';

/// A ReadiumReaderWidget wraps a native Kotlin/Swift Readium navigator widget.
class ReadiumReaderWidget extends StatefulWidget {
  const ReadiumReaderWidget({
    required this.publication,
    this.loadingWidget = const Center(child: CircularProgressIndicator()),
    this.initialLocator,
    this.onTap,
    this.onDoubleTap,
    this.onGoLeft,
    this.onGoRight,
    this.onSwipe,
    this.onExternalLinkActivated,
    this.onLocatorChanged,
    this.onReady,
    super.key,
  });

  final Publication publication;
  final Widget loadingWidget;
  final Locator? initialLocator;
  final VoidCallback? onTap;
  final VoidCallback? onDoubleTap;
  final VoidCallback? onGoLeft;
  final VoidCallback? onGoRight;
  final VoidCallback? onSwipe;
  final Function(String)? onExternalLinkActivated;
  final void Function(Locator)? onLocatorChanged;

  /// Called once when the native visual navigator reports that it is ready.
  final VoidCallback? onReady;

  @override
  State<StatefulWidget> createState() => _ReadiumReaderWidgetState();
}

class _ReadiumReaderWidgetState extends State<ReadiumReaderWidget>
    with WakelockManagerMixin, ReaderLifecycleMixin, OrientationHandlerMixin
    implements ReadiumReaderWidgetInterface {
  ReadiumReaderChannel? _channel;
  StreamSubscription<Locator>? _locatorDebugSub;
  bool wasDestroyed = false;
  bool isReady = false;
  bool _readyNotified = false;
  int? _tapPointer;
  Offset? _tapOrigin;
  bool _tapMoved = false;
  Duration? _tapDownTime;
  final ReaderDoubleTapTracker _doubleTapTracker = ReaderDoubleTapTracker();

  static const _maximumTapDuration = Duration(milliseconds: 350);

  final _isReadyCompleter = Completer<void>();

  late Widget _readerWidget;

  EPUBPreferences? get _defaultPreferences {
    return readium.defaultPreferences;
  }

  PDFPreferences? get _defaultPdfPreferences {
    return readium.defaultPdfPreferences;
  }

  @override
  void initState() {
    super.initState();
    R2Log.d('ReadiumReaderWidget initiated');

    _readerWidget = _buildNativeReader();
    _currentLocator = widget.initialLocator;
    enableWakelock();
    // setCurrentWidgetInterface is called in _onPlatformViewCreated after
    // _channel is assigned, so that callers receive a widget whose channel
    // is ready to send method calls.
  }

  @override
  void dispose() {
    R2Log.d('ReadiumReaderWidget disposed');
    _doubleTapTracker.cancel();
    _locatorDebugSub?.cancel();
    _locatorDebugSub = null;
    cleanupWidgetInterface(_channel?.name);
    // Android owns the native PlatformView lifecycle. Calling its explicit
    // dispose method here as well races with the framework callback and can
    // close a newly opened publication. Keep the explicit cleanup for other
    // native implementations whose view lifecycle may require it.
    unawaited(_channel?.dispose(notifyNative: !Platform.isAndroid));
    _channel = null;
    lastOrientation = null;

    disableWakelock();
    wasDestroyed = true;

    super.dispose();
  }

  @override
  Widget build(final BuildContext context) {
    handleOrientationChange(
      currentOrientation: MediaQuery.orientationOf(context),
      isReady: isReady,
      currentLocator: _currentLocator,
      channel: _channel,
    );

    return Listener(
      onPointerDown: (final event) {
        R2Log.d('[TAP-DEBUG] Listener onPointerDown at ${event.position}');
        enableWakelock();
        _tapPointer = event.pointer;
        _tapOrigin = event.localPosition;
        _tapMoved = false;
        _tapDownTime = event.timeStamp;
      },
      onPointerMove: (final event) {
        if (_tapPointer != event.pointer || _tapOrigin == null) return;
        if ((event.localPosition - _tapOrigin!).distance > 18) _tapMoved = true;
      },
      onPointerUp: (final event) {
        R2Log.d('[TAP-DEBUG] Listener onPointerUp at ${event.position}');
        final tapDuration = _tapDownTime == null
            ? Duration.zero
            : event.timeStamp - _tapDownTime!;
        if (_tapPointer == event.pointer &&
            !_tapMoved &&
            tapDuration <= _maximumTapDuration) {
          final renderObject = context.findRenderObject();
          final surfaceSize = renderObject is RenderBox
              ? renderObject.size
              : Size.zero;
          _doubleTapTracker.registerTap(
            position: event.localPosition,
            surfaceSize: surfaceSize,
            onSingleTap: widget.onTap,
            onDoubleTap: widget.onDoubleTap,
          );
        }
        _clearTapTracking();
      },
      onPointerCancel: (_) => _clearTapTracking(),
      child: _readerWidget,
    );
  }

  @override
  Future<void> go(
    final Locator locator, {
    required final bool isAudioBookWithText,
    final bool animated = false,
  }) async {
    R2Log.d(() => 'Go to $locator');

    await _channel?.go(
      locator,
      animated: animated,
      isAudioBookWithText: isAudioBookWithText,
    );

    R2Log.d('Done');
  }

  @override
  Future<void> goLeft({final bool animated = true}) async => _channel?.goLeft();

  @override
  Future<void> goRight({final bool animated = true}) async =>
      _channel?.goRight();

  @override
  Future<void> skipToNext({final bool animated = true}) async {
    final toc = widget.publication.toc;
    if (toc.isEmpty || _currentLocator == null) {
      R2Log.d('skipToNext: no TOC or no current locator');
      return;
    }

    int curIndex = -1;

    // Priority 1: stored index from last chapter navigation
    if (_lastNavigatedTocIndex != null &&
        _lastNavigatedTocIndex! < toc.length) {
      final expectedPath = normalizePath(toc[_lastNavigatedTocIndex!].hrefPart);
      final currentPath = normalizePath(_currentLocator!.hrefPath);
      if (currentPath == expectedPath) {
        curIndex = _lastNavigatedTocIndex!;
        R2Log.d('skipToNext: using stored index $curIndex');
      } else {
        R2Log.d('skipToNext: stored index invalid (file changed)');
        _lastNavigatedTocIndex = null;
      }
    }

    // Priority 2: toc= fragment matching (sub-chapter granularity)
    if (curIndex == -1) {
      final currentHref = getTextLocatorHrefWithTocFragment(_currentLocator);
      if (currentHref != null) {
        curIndex = toc.indexWhere((l) => l.href == currentHref);
      }
    }

    // Priority 3: path-based fallback (file-level granularity)
    if (curIndex == -1) {
      R2Log.d('skipToNext: toc= fragment matching failed, using fallback');
      // Check if this is a PDF (page-based matching)
      if (isPdfToc(toc)) {
        curIndex = findTocIndexByPage(_currentLocator!, toc);
        R2Log.d('skipToNext: PDF page matching returned index $curIndex');
      } else {
        curIndex = findTocIndexByPath(_currentLocator!, toc, lastMatch: true);
      }
    }

    R2Log.d('skipToNext: curIndex=$curIndex, tocLength=${toc.length}');

    // Use navigation helper to decide where to navigate
    final decision = decideSkipToNext(
      currentLocator: _currentLocator!,
      toc: toc,
      readingOrder: widget.publication.readingOrder,
      currentTocIndex: curIndex,
      publication: widget.publication,
    );

    if (!decision.canNavigate) {
      R2Log.d('skipToNext: ${decision.reason}');
      return;
    }

    // Navigate to the target
    final targetLocator = widget.publication.locatorFromLink(
      decision.targetLink!,
    );
    if (targetLocator != null) {
      R2Log.d('skipToNext: navigating to ${decision.targetLink!.href}');
      await _channel?.go(
        targetLocator,
        isAudioBookWithText: false,
        animated: true,
      );
      _lastNavigatedTocIndex = decision.targetTocIndex;
    }
  }

  @override
  Future<void> skipToPrevious({final bool animated = true}) async {
    final toc = widget.publication.toc;
    if (toc.isEmpty || _currentLocator == null) {
      R2Log.d('skipToPrevious: no TOC or no current locator');
      return;
    }

    int curIndex = -1;

    // Priority 1: stored index from last chapter navigation
    if (_lastNavigatedTocIndex != null &&
        _lastNavigatedTocIndex! < toc.length) {
      final expectedPath = normalizePath(toc[_lastNavigatedTocIndex!].hrefPart);
      final currentPath = normalizePath(_currentLocator!.hrefPath);
      if (currentPath == expectedPath) {
        curIndex = _lastNavigatedTocIndex!;
        R2Log.d('skipToPrevious: using stored index $curIndex');
      } else {
        R2Log.d('skipToPrevious: stored index invalid (file changed)');
        _lastNavigatedTocIndex = null;
      }
    }

    // Priority 2: toc= fragment matching (sub-chapter granularity)
    if (curIndex == -1) {
      final currentHref = getTextLocatorHrefWithTocFragment(_currentLocator);
      if (currentHref != null) {
        curIndex = toc.indexWhere((l) => l.href == currentHref);
      }
    }

    // Priority 3: path-based fallback (file-level granularity)
    if (curIndex == -1) {
      R2Log.d('skipToPrevious: toc= fragment matching failed, using fallback');
      // Check if this is a PDF (page-based matching)
      if (isPdfToc(toc)) {
        curIndex = findTocIndexByPage(_currentLocator!, toc);
        R2Log.d('skipToPrevious: PDF page matching returned index $curIndex');
      } else {
        curIndex = findTocIndexByPath(_currentLocator!, toc, lastMatch: false);
      }
    }

    R2Log.d('skipToPrevious: curIndex=$curIndex, tocLength=${toc.length}');

    // Use navigation helper to decide where to navigate
    final decision = decideSkipToPrevious(
      currentLocator: _currentLocator!,
      toc: toc,
      readingOrder: widget.publication.readingOrder,
      currentTocIndex: curIndex,
      publication: widget.publication,
    );

    if (!decision.canNavigate) {
      R2Log.d('skipToPrevious: ${decision.reason}');
      return;
    }

    // Navigate to the target
    final targetLocator = widget.publication.locatorFromLink(
      decision.targetLink!,
    );
    if (targetLocator != null) {
      R2Log.d('skipToPrevious: navigating to ${decision.targetLink!.href}');
      await _channel?.go(
        targetLocator,
        isAudioBookWithText: false,
        animated: true,
      );
      _lastNavigatedTocIndex = decision.targetTocIndex;
    }
  }

  @override
  Future<Locator?> getLocatorFragments(final Locator locator) async {
    R2Log.d('getLocatorFragments: $locator');

    await _awaitNativeViewReady();

    return await _channel?.getLocatorFragments(locator);
  }

  @override
  Future<Locator?> getCurrentLocator() async {
    R2Log.d('GetCurrentLocator()');
    return _channel?.getCurrentLocator();
  }

  @override
  Future<void> setEPUBPreferences(EPUBPreferences preferences) async {
    _channel?.setEPUBPreferences(preferences);
  }

  @override
  Future<void> setPDFPreferences(PDFPreferences preferences) async {
    _channel?.setPDFPreferences(preferences);
  }

  @override
  Future<void> setNavigationConfig(ReaderNavigationConfig config) async {
    _channel?.setNavigationConfig(config);
  }

  @override
  Future<void> applyDecorations(
    String id,
    List<ReaderDecoration> decorations,
  ) async {
    await _channel?.applyDecorations(id, decorations);
  }

  Widget _buildNativeReader() {
    final publication = widget.publication;

    R2Log.d(publication.identifier);

    // Check if this is a PDF publication by looking at reading order media types
    final isPdf = publication.readingOrder.any(
      (link) => link.type?.contains('pdf') ?? false,
    );

    // Use PDF preferences for PDF publications, EPUB preferences for others
    final defaultPreferences = isPdf
        ? _defaultPdfPreferences?.toJson()
        : _defaultPreferences?.toJson();

    final creationParams = <String, dynamic>{
      'pubIdentifier': publication.identifier,
      'preferences': defaultPreferences,
      'initialLocator': widget.initialLocator == null
          ? null
          : json.encode(widget.initialLocator!.toJson()),
    };

    R2Log.d('creationParams=$creationParams');

    if (Platform.isAndroid) {
      return PlatformViewLink(
        viewType: _viewType,
        surfaceFactory: (final context, final controller) => AndroidViewSurface(
          controller: controller as AndroidViewController,
          gestureRecognizers: const {},
          hitTestBehavior: PlatformViewHitTestBehavior.opaque,
        ),
        onCreatePlatformView: (final params) =>
            PlatformViewsService.initSurfaceAndroidView(
                id: params.id,
                viewType: _viewType,
                layoutDirection: TextDirection.ltr,
                creationParams: creationParams,
                creationParamsCodec: const StandardMessageCodec(),
              )
              ..addOnPlatformViewCreatedListener(params.onPlatformViewCreated)
              ..addOnPlatformViewCreatedListener(_onPlatformViewCreated)
              ..create(),
      );
    } else if (Platform.isIOS) {
      return UiKitView(
        viewType: _viewType,
        layoutDirection: TextDirection.ltr,
        creationParams: creationParams,
        creationParamsCodec: const StandardMessageCodec(),
        onPlatformViewCreated: _onPlatformViewCreated,
      );
    }
    return ColoredBox(
      color: const Color(0xffff00ff),
      child: Center(
        child: Text(
          'TODO — Implement ReadiumReaderWidget on ${Platform.operatingSystem}.',
        ),
      ),
    );
  }

  Locator? _currentLocator;

  /// Tracks the last TOC index navigated to via skipToNext/skipToPrevious.
  /// Used as highest-priority source for determining current position,
  /// since the JS-reported toc= heading may differ from the navigation target.
  int? _lastNavigatedTocIndex;

  void _onPlatformViewCreated(final int id) {
    _channel = ReadiumReaderChannel(
      '$_viewType:$id',
      onReaderReady: _markReaderReady,
      onPageChanged: (final locator) {
        debugPrint('onPageChanged: ${locator.toJson()}');
        _currentLocator = locator;
        widget.onLocatorChanged?.call(locator);

        // Fallback for platforms/plugin versions without the explicit ready
        // callback: a locator proves that the navigator is operational.
        _markReaderReady();
      },
    );

    // Register as current widget only after _channel is assigned.
    // This ensures waitForCurrentReaderWidget() callers receive a widget
    // whose channel is ready, so method calls aren't silently dropped.
    setCurrentWidgetInterface(this);

    // iOS currently has no explicit instance-ready method. Preserve its
    // established platform-view-created behavior; Android waits for the
    // navigator callback (or the first locator above).
    if (Platform.isAndroid) {
      unawaited(_probeNativeReady());
    } else {
      _markReaderReady();
    }

    R2Log.d('New widget is: ${_channel?.name}');

    // TODO: This is just to demo how to use and debounce the Stream, remove when appropriate.
    final nativeLocatorStream = readium.onTextLocatorChanged
        .debounceTime(const Duration(milliseconds: 50))
        .asBroadcastStream()
        .distinct();

    _locatorDebugSub?.cancel();
    _locatorDebugSub = nativeLocatorStream.listen((locator) {
      R2Log.d('ReaderWidget.LocatorChanged - $locator');
    });
  }

  Future _awaitNativeViewReady() {
    return _isReadyCompleter.future;
  }

  void _markReaderReady() {
    if (_readyNotified || wasDestroyed) return;
    _readyNotified = true;
    if (mounted) setState(() => isReady = true);
    if (!_isReadyCompleter.isCompleted) _isReadyCompleter.complete();
    widget.onReady?.call();
  }

  Future<void> _probeNativeReady() async {
    // The native navigator can become ready just before Dart installs its
    // method handler. Polling its current locator closes that small race.
    for (var attempt = 0; attempt < 40; attempt++) {
      if (_readyNotified || wasDestroyed) return;
      try {
        final locator = await _channel?.getCurrentLocator();
        if (locator != null) {
          _currentLocator = locator;
          widget.onLocatorChanged?.call(locator);
          _markReaderReady();
          return;
        }
      } on Object catch (_) {
        // Navigator creation is asynchronous; retry until it is available.
      }
      await Future<void>.delayed(const Duration(milliseconds: 100));
    }
  }

  void _clearTapTracking() {
    _tapPointer = null;
    _tapOrigin = null;
    _tapMoved = false;
    _tapDownTime = null;
  }

  /// Gets a Locator's href with toc fragment appended as identifier
  String? getTextLocatorHrefWithTocFragment(Locator? locator) {
    if (locator == null) {
      return null;
    }

    final txtLoc = locator.toTextLocator();
    final tocFragment = locator.locations?.fragments.firstWhereOrNull(
      (f) => f.startsWith("toc="),
    );
    if (tocFragment == null) {
      return null;
    }
    return '${txtLoc.toTextLocator().hrefPath}#${tocFragment.substring(4)}';
  }
}
