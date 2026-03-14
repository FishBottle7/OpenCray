class OpenCrayLlmValidationResult {
  const OpenCrayLlmValidationResult({
    required this.isSuccess,
    required this.message,
  });

  final bool isSuccess;
  final String message;

  factory OpenCrayLlmValidationResult.fromMap(Map<Object?, Object?> payload) {
    return OpenCrayLlmValidationResult(
      isSuccess: payload['isSuccess'] as bool? ?? false,
      message: payload['message'] as String? ?? '',
    );
  }
}
