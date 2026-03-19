import 'dart:async';

import 'package:just_audio/just_audio.dart';

class ChatVoicePlaybackSnapshot {
  const ChatVoicePlaybackSnapshot({
    this.isLoading = false,
    this.isPlaying = false,
    this.position = Duration.zero,
    this.duration = Duration.zero,
    this.errorMessage,
  });

  final bool isLoading;
  final bool isPlaying;
  final Duration position;
  final Duration duration;
  final String? errorMessage;

  ChatVoicePlaybackSnapshot copyWith({
    bool? isLoading,
    bool? isPlaying,
    Duration? position,
    Duration? duration,
    String? errorMessage,
    bool clearError = false,
  }) {
    return ChatVoicePlaybackSnapshot(
      isLoading: isLoading ?? this.isLoading,
      isPlaying: isPlaying ?? this.isPlaying,
      position: position ?? this.position,
      duration: duration ?? this.duration,
      errorMessage: clearError ? null : (errorMessage ?? this.errorMessage),
    );
  }
}

abstract interface class ChatVoicePlaybackController {
  ChatVoicePlaybackSnapshot get currentState;

  Stream<ChatVoicePlaybackSnapshot> get snapshots;

  Future<void> setSource({required String filePath});

  Future<void> play();

  Future<void> pause();

  Future<void> seek(Duration position);

  Future<void> dispose();
}

typedef ChatVoicePlaybackControllerFactory =
    ChatVoicePlaybackController Function();

ChatVoicePlaybackController createDefaultChatVoicePlaybackController() =>
    _JustAudioChatVoicePlaybackController();

class _JustAudioChatVoicePlaybackController
    implements ChatVoicePlaybackController {
  _JustAudioChatVoicePlaybackController() {
    _subscriptions.add(_player.playerStateStream.listen((_) => _emit()));
    _subscriptions.add(_player.positionStream.listen((_) => _emit()));
    _subscriptions.add(_player.durationStream.listen((_) => _emit()));
  }

  final AudioPlayer _player = AudioPlayer();
  final StreamController<ChatVoicePlaybackSnapshot> _snapshots =
      StreamController<ChatVoicePlaybackSnapshot>.broadcast();
  final List<StreamSubscription<dynamic>> _subscriptions =
      <StreamSubscription<dynamic>>[];

  ChatVoicePlaybackSnapshot _state = const ChatVoicePlaybackSnapshot();
  String? _sourcePath;

  @override
  ChatVoicePlaybackSnapshot get currentState => _state;

  @override
  Stream<ChatVoicePlaybackSnapshot> get snapshots => _snapshots.stream;

  @override
  Future<void> setSource({required String filePath}) async {
    if (_sourcePath == filePath && filePath.trim().isNotEmpty) {
      return;
    }
    _state = _state.copyWith(isLoading: true, clearError: true);
    _snapshots.add(_state);
    try {
      await _player.setFilePath(filePath);
      _sourcePath = filePath;
      _emit();
    } catch (error) {
      _state = _state.copyWith(
        isLoading: false,
        isPlaying: false,
        errorMessage: '$error',
      );
      _snapshots.add(_state);
      rethrow;
    }
  }

  @override
  Future<void> play() async {
    try {
      if (_player.processingState == ProcessingState.completed) {
        await _player.seek(Duration.zero);
      }
      await _player.play();
      _emit();
    } catch (error) {
      _state = _state.copyWith(errorMessage: '$error');
      _snapshots.add(_state);
      rethrow;
    }
  }

  @override
  Future<void> pause() async {
    try {
      await _player.pause();
      _emit();
    } catch (error) {
      _state = _state.copyWith(errorMessage: '$error');
      _snapshots.add(_state);
      rethrow;
    }
  }

  @override
  Future<void> seek(Duration position) async {
    try {
      final Duration duration = _player.duration ?? Duration.zero;
      final Duration clamped = duration > Duration.zero
          ? Duration(
              milliseconds: position.inMilliseconds.clamp(
                0,
                duration.inMilliseconds,
              ),
            )
          : Duration(milliseconds: position.inMilliseconds.clamp(0, 1 << 31));
      await _player.seek(clamped);
      _emit();
    } catch (error) {
      _state = _state.copyWith(errorMessage: '$error');
      _snapshots.add(_state);
      rethrow;
    }
  }

  @override
  Future<void> dispose() async {
    for (final subscription in _subscriptions) {
      await subscription.cancel();
    }
    await _player.dispose();
    await _snapshots.close();
  }

  void _emit() {
    final Duration duration = _player.duration ?? Duration.zero;
    final Duration position = _player.position > duration
        ? duration
        : _player.position;
    _state = _state.copyWith(
      isLoading:
          _player.processingState == ProcessingState.loading ||
          _player.processingState == ProcessingState.buffering,
      isPlaying: _player.playing,
      position: position,
      duration: duration,
      clearError: true,
    );
    _snapshots.add(_state);
  }
}
