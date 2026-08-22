part of 'chat_feature_screen.dart';

String? _visibleAssistantDraftText(String rawText) {
  final String normalized = rawText.trim();
  if (normalized.isEmpty) {
    return null;
  }
  final bool startsLikeJson =
      normalized.startsWith('{') || normalized.startsWith('[');
  if (!startsLikeJson) {
    return normalized;
  }
  final String lowercase = normalized.toLowerCase();
  final bool hasExplicitTypeField =
      lowercase.contains('"type"') || lowercase.contains('"decision"');
  final bool looksLikeStructuredProtocol =
      hasExplicitTypeField ||
      lowercase.contains('"actions"') ||
      lowercase.contains('"tool_name"') ||
      lowercase.contains('"tool_calls"') ||
      lowercase.contains('"function_call"') ||
      lowercase.contains('"call_id"') ||
      lowercase.contains('"arguments"');
  final bool looksLikeInternalSignal =
      lowercase.contains('"is_task_bearing_request"') ||
      lowercase.contains('"user_affect"') ||
      lowercase.contains('"user_invites_playfulness"') ||
      lowercase.contains('"user_requests_relational_support"') ||
      lowercase.contains('"clarification_needed"');
  if (!looksLikeStructuredProtocol && !looksLikeInternalSignal) {
    return normalized;
  }
  if (looksLikeInternalSignal ||
      lowercase.contains('"tool_name"') ||
      lowercase.contains('"tool_calls"') ||
      lowercase.contains('"function_call"') ||
      lowercase.contains('"call_id"') ||
      lowercase.contains('"arguments"')) {
    return null;
  }
  final String? actionType = _firstNonBlankDraftField(<String?>[
    _partialJsonStringFieldValue(normalized, 'type')?.trim().toLowerCase(),
    _partialJsonStringFieldValue(normalized, 'decision')?.trim().toLowerCase(),
  ]);
  switch (actionType) {
    case 'final':
    case 'answer':
      return _firstNonBlankDraftField(<String?>[
        _partialJsonStringFieldValue(normalized, 'answer'),
        _partialJsonStringFieldValue(normalized, 'text'),
        _partialJsonStringFieldValue(normalized, 'message'),
        _partialJsonStringFieldValue(normalized, 'summary'),
      ])?.trim();
    case null:
    case '':
      return hasExplicitTypeField
          ? _partialJsonStringFieldValue(normalized, 'answer')?.trim()
          : null;
    default:
      return null;
  }
}

String? _firstNonBlankDraftField(List<String?> values) {
  for (final String? value in values) {
    final String trimmed = value?.trim() ?? '';
    if (trimmed.isNotEmpty) {
      return trimmed;
    }
  }
  return null;
}

String? _partialJsonStringFieldValue(String rawText, String fieldName) {
  final String fieldPattern = '"$fieldName"';
  int searchStart = 0;
  while (true) {
    final int keyIndex = rawText.indexOf(fieldPattern, searchStart);
    if (keyIndex < 0) {
      return null;
    }
    int index = keyIndex + fieldPattern.length;
    while (index < rawText.length && rawText[index].trim().isEmpty) {
      index += 1;
    }
    if (index >= rawText.length || rawText[index] != ':') {
      searchStart = keyIndex + fieldPattern.length;
      continue;
    }
    index += 1;
    while (index < rawText.length && rawText[index].trim().isEmpty) {
      index += 1;
    }
    if (index >= rawText.length || rawText[index] != '"') {
      return null;
    }
    index += 1;
    final StringBuffer buffer = StringBuffer();
    bool escaped = false;
    while (index < rawText.length) {
      final String character = rawText[index];
      if (escaped) {
        switch (character) {
          case 'n':
            buffer.write('\n');
            break;
          case 'r':
            buffer.write('\r');
            break;
          case 't':
            buffer.write('\t');
            break;
          default:
            buffer.write(character);
            break;
        }
        escaped = false;
        index += 1;
        continue;
      }
      if (character == '\\') {
        escaped = true;
        index += 1;
        continue;
      }
      if (character == '"') {
        return buffer.toString();
      }
      buffer.write(character);
      index += 1;
    }
    return buffer.toString();
  }
}
