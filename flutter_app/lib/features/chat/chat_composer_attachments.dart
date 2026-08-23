// ignore_for_file: invalid_use_of_protected_member
part of 'chat_feature_screen.dart';

extension _ChatComposerAttachmentsActions on _OpenCrayChatFeatureState {
  void _removeAttachment(ChatAttachmentData attachment) {
    setState(() {
      final attachments = List<ChatAttachmentData>.of(
        _state.composer.attachments,
      )..removeWhere((ChatAttachmentData item) => item.id == attachment.id);
      _state = _state.copyWith(
        composer: _state.composer.copyWith(attachments: attachments),
      );
    });
  }

  OpenCrayChatDraftAttachmentKind _attachmentKindForAction(String label) {
    if (label == widget.copy.chatActionImage) {
      return OpenCrayChatDraftAttachmentKind.image;
    }
    return OpenCrayChatDraftAttachmentKind.file;
  }

  ChatAttachmentData _attachmentForAction(String label) {
    if (label == widget.copy.chatActionImage) {
      return OpenCrayChatSeedData.sampleAttachments(widget.copy).firstWhere(
        (ChatAttachmentData item) => item.kind == ChatAttachmentKind.image,
      );
    }
    return OpenCrayChatSeedData.sampleAttachments(widget.copy).firstWhere(
      (ChatAttachmentData item) => item.kind == ChatAttachmentKind.file,
    );
  }

  ChatAttachmentData _draftAttachmentForComposer(
    OpenCrayChatDraftAttachment attachment,
  ) {
    final bool isImage =
        attachment.kind == OpenCrayChatDraftAttachmentKind.image;
    final bool isVoice =
        attachment.kind == OpenCrayChatDraftAttachmentKind.voice;
    final String detail =
        attachment.sizeBytes != null && attachment.sizeBytes! >= 0
        ? _formatAttachmentBytes(attachment.sizeBytes!)
        : attachment.durationMs != null && attachment.durationMs! > 0
        ? _formatAttachmentDuration(attachment.durationMs!)
        : (widget.copy.isChinese
              ? (isImage ? '图片附件' : (isVoice ? '语音附件' : '文件附件'))
              : (isImage
                    ? 'Image attachment'
                    : (isVoice ? 'Voice attachment' : 'File attachment')));
    return ChatAttachmentData(
      id: attachment.id,
      kind: isImage
          ? ChatAttachmentKind.image
          : (isVoice ? ChatAttachmentKind.voice : ChatAttachmentKind.file),
      label: attachment.displayName,
      detail: detail,
      accentColor: isImage
          ? OpenCrayColors.primaryTint
          : (isVoice ? OpenCrayColors.successTint : OpenCrayColors.surfaceMuted),
      draftAttachment: attachment,
    );
  }

  OpenCrayChatDraftAttachment? _draftAttachmentForMessageAttachment(
    ChatMessageAttachmentData attachment,
  ) {
    final String chatAttachmentId = attachment.attachmentId.trim();
    final String relativePath = attachment.localPath.trim();
    if (chatAttachmentId.isEmpty && relativePath.isEmpty) {
      return null;
    }
    return OpenCrayChatDraftAttachment(
      kind: switch (attachment.kind) {
        ChatAttachmentKind.image => OpenCrayChatDraftAttachmentKind.image,
        ChatAttachmentKind.voice => OpenCrayChatDraftAttachmentKind.voice,
        ChatAttachmentKind.file => OpenCrayChatDraftAttachmentKind.file,
      },
      displayName: attachment.displayName,
      relativePath: relativePath,
      chatAttachmentId: chatAttachmentId.isEmpty ? null : chatAttachmentId,
      mimeType: attachment.mimeType,
      sizeBytes: attachment.sizeBytes,
      durationMs: attachment.durationMs,
      waveformBars: attachment.waveformBars,
      transcriptText: attachment.transcriptText,
    );
  }

  List<OpenCrayChatDraftAttachment> _draftAttachmentsForMessage(
    ChatMessageData message,
  ) {
    return message.attachments
        .map(_draftAttachmentForMessageAttachment)
        .whereType<OpenCrayChatDraftAttachment>()
        .toList(growable: false);
  }

  List<ChatAttachmentData> _composerAttachmentsForMessage(
    ChatMessageData message,
  ) {
    return _draftAttachmentsForMessage(
      message,
    ).map(_draftAttachmentForComposer).toList(growable: false);
  }

  int get _currentComposerImageCount =>
      _state.composer.attachments.where((ChatAttachmentData attachment) {
        return attachment.kind == ChatAttachmentKind.image;
      }).length;

  String _attachmentDuplicateMessage(int duplicateCount) {
    if (widget.copy.isChinese) {
      return '已自动忽略 $duplicateCount 个重复附件。';
    }
    return duplicateCount == 1
        ? 'Ignored 1 duplicate attachment.'
        : 'Ignored $duplicateCount duplicate attachments.';
  }

  String _attachmentImageLimitMessage({required int skippedCount}) {
    if (widget.copy.isChinese) {
      return skippedCount > 0
          ? '单条消息最多添加 $_chatComposerMaxImageAttachments 张图片，已忽略 $skippedCount 张。'
          : '单条消息最多添加 $_chatComposerMaxImageAttachments 张图片。';
    }
    return skippedCount > 0
        ? 'Each message supports up to $_chatComposerMaxImageAttachments images. Skipped $skippedCount.'
        : 'Each message supports up to $_chatComposerMaxImageAttachments images.';
  }

  String _attachmentPickerFailureMessage(Object error) {
    final explicitMessage = switch (error) {
      PlatformException(:final message?) => _normalizeAttachmentErrorMessage(
        message,
      ),
      UnsupportedError(:final message?) => _normalizeAttachmentErrorMessage(
        message,
      ),
      _ => _normalizeAttachmentErrorMessage(error.toString()),
    };
    if (explicitMessage != null) {
      return explicitMessage;
    }
    return widget.copy.isChinese ? '无法添加附件。' : 'Unable to add attachment.';
  }

  String? _normalizeAttachmentErrorMessage(String rawMessage) {
    var message = rawMessage.trim();
    if (message.isEmpty) {
      return null;
    }
    if (message.startsWith('Bad state: ')) {
      return null;
    }
    if (message.startsWith('Unsupported operation: ')) {
      message = message.substring('Unsupported operation: '.length).trim();
    }
    if (message.startsWith('HttpException: ')) {
      message = message.substring('HttpException: '.length).trim();
    }
    message = message.replaceFirst(RegExp(r', uri = .*$'), '').trim();
    final localRuntimeMatch = RegExp(
      r'^Local runtime returned HTTP \d+: (.+)$',
    ).firstMatch(message);
    if (localRuntimeMatch != null) {
      message = localRuntimeMatch.group(1)?.trim() ?? '';
    }
    if (message.startsWith('{') && message.endsWith('}')) {
      final decoded = _tryDecodeAttachmentErrorMessage(message);
      if (decoded != null) {
        message = decoded;
      }
    }
    return message.isEmpty ? null : message;
  }

  String? _tryDecodeAttachmentErrorMessage(String payload) {
    try {
      final decoded = jsonDecode(payload);
      if (decoded is Map<Object?, Object?>) {
        final error = decoded['error'] as String?;
        return error?.trim().isNotEmpty == true ? error!.trim() : null;
      }
    } catch (_) {}
    return null;
  }

  void _showComposerNotice(String message) {
    final bridge = widget.bridge;
    if (bridge != null) {
      unawaited(bridge.showNativeToast(message));
      return;
    }
    ScaffoldMessenger.of(
      context,
    ).showSnackBar(SnackBar(content: Text(message)));
  }
}
