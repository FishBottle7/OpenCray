import 'package:flutter/services.dart';
import 'dart:async';
import 'dart:io';

import 'opencray_failure_bridge.dart';
import 'opencray_host_bridge.dart';
import 'opencray_local_runtime_bridge.dart';
import 'opencray_platform_bridge.dart';
import 'opencray_seed_bridge.dart';

abstract interface class OpenCrayHostBridgeConnector {
  Future<OpenCrayHostBridge?> connect();
}

class OpenCrayHostBridgeBootstrap {
  static Future<OpenCrayHostBridge> bootstrap({
    List<OpenCrayHostBridgeConnector>? connectors,
    bool? allowSeedFallback,
  }) async {
    final runtimeConnectors = connectors ?? _defaultConnectors;
    final shouldAllowSeedFallback = allowSeedFallback ?? !Platform.isAndroid;
    Object? lastFailure;
    for (final connector in runtimeConnectors) {
      try {
        final bridge = await connector.connect();
        if (bridge != null) {
          return bridge;
        }
      } on MissingPluginException {
        continue;
      } on PlatformException catch (error) {
        return OpenCrayFailureBridge(
          failureMessage:
              error.message ?? 'Host runtime reported a platform error.',
        );
      } catch (error) {
        lastFailure = error;
        break;
      }
    }
    if (shouldAllowSeedFallback) {
      return OpenCraySeedBridge();
    }
    return OpenCrayFailureBridge(
      failureMessage:
          lastFailure?.toString() ?? 'No local runtime bridge is available.',
    );
  }

  static const List<OpenCrayHostBridgeConnector> _defaultConnectors =
      <OpenCrayHostBridgeConnector>[
        OpenCrayPlatformBridgeConnector(),
        OpenCrayLocalRuntimeBridgeConnector(),
      ];
}

class OpenCrayLocalRuntimeBridgeConnector
    implements OpenCrayHostBridgeConnector {
  const OpenCrayLocalRuntimeBridgeConnector({
    this.baseUrl,
    this.requestTimeout = const Duration(milliseconds: 800),
    this.pollInterval = const Duration(seconds: 2),
  });

  final String? baseUrl;
  final Duration requestTimeout;
  final Duration pollInterval;

  @override
  Future<OpenCrayHostBridge?> connect() async {
    final configuredBaseUrl = (baseUrl ?? _environmentBaseUrl).trim();
    if (configuredBaseUrl.isEmpty && Platform.isAndroid) {
      // The production Android loopback endpoint uses an ephemeral port and
      // per-start credentials held by the native process. If the platform
      // channel is unavailable, Dart cannot authenticate to that endpoint.
      return null;
    }
    final resolvedBaseUrl = configuredBaseUrl.isEmpty
        ? _defaultBaseUrl
        : configuredBaseUrl;
    final bridge = OpenCrayLocalRuntimeBridge(
      baseUrl: resolvedBaseUrl,
      requestTimeout: requestTimeout,
      pollInterval: pollInterval,
    );
    try {
      await bridge.loadShellSnapshot();
      return bridge;
    } on IOException {
      return null;
    } on TimeoutException {
      return null;
    } on FormatException {
      return null;
    }
  }

  static const String _environmentBaseUrl = String.fromEnvironment(
    'OPENCRAY_LOCAL_RUNTIME_BASE_URL',
    defaultValue: '',
  );
  static const String _defaultBaseUrl = 'http://127.0.0.1:42617/';
}

class OpenCrayPlatformBridgeConnector implements OpenCrayHostBridgeConnector {
  const OpenCrayPlatformBridgeConnector();

  @override
  Future<OpenCrayHostBridge?> connect() async {
    const bridge = OpenCrayPlatformBridge();
    await bridge.loadShellSnapshot();
    return bridge;
  }
}
