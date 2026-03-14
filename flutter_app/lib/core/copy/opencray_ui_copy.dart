import '../../app/opencray_tabs.dart';

class OpenCrayUiCopy {
  const OpenCrayUiCopy._({required this.localeTag, required this.isChinese});

  factory OpenCrayUiCopy.fromLocaleTag(String localeTag) {
    final normalized = localeTag.trim().toLowerCase();
    final isChinese = normalized.startsWith('zh');
    return OpenCrayUiCopy._(
      localeTag: normalized.isEmpty ? 'en' : normalized,
      isChinese: isChinese,
    );
  }

  final String localeTag;
  final bool isChinese;

  String tabLabel(OpenCrayTab tab) {
    switch (tab) {
      case OpenCrayTab.chat:
        return isChinese ? '对话' : 'Chat';
      case OpenCrayTab.skills:
        return isChinese ? '技能' : 'Skills';
      case OpenCrayTab.files:
        return isChinese ? '文件' : 'Files';
      case OpenCrayTab.settings:
        return isChinese ? '设置' : 'Settings';
    }
  }

  String get filesTitle => isChinese ? '文件' : 'Files';
  String get filesSearchHint =>
      isChinese ? '搜索文件和文件夹' : 'Search files and folders';
  String get filesLocationTitle => isChinese ? '位置' : 'Location';
  String get filesLocationPath => 'OpenCray / src / main';
  String get filesLocationItemCount => isChinese ? '622 项' : '622 items';
  String get filesLocationAvailableSpace =>
      isChinese ? '可用 4.1 GB' : '4.1 GB available';
  String filesDirectoryItemCount(int count) =>
      isChinese ? '$count 项' : '$count items';
  String get filesEntryMetaChat =>
      isChinese ? '35 KB   00:58' : '35 KB   12:58 AM';
  String get filesEntryMetaSkills =>
      isChinese ? '21 KB   昨天' : '21 KB   Yesterday';
  String get filesEntryMetaSpec =>
      isChinese ? '12 KB   3月11日' : '12 KB   Mar 11';

  String get skillsEyebrow => isChinese ? '自动化库' : 'AUTOMATION LIBRARY';
  String get skillsTitle => isChinese ? '技能' : 'Skills';
  String get skillsManageSubtitle => isChinese
      ? '管理这个工作区里已经安装的技能包。'
      : 'Manage the installed skill packages for this workspace.';
  String get skillsInstallSubtitle => isChinese
      ? '当本地目录里存在可用项时，从中安装更多技能。'
      : 'Install additional skills from the local catalog when one is available.';
  String get skillsNoInstalledTitle =>
      isChinese ? '还没有已安装技能' : 'No installed skills';
  String get skillsNoInstalledBody => isChinese
      ? '当前宿主运行时还没有向这个工作区暴露任何可管理技能。'
      : 'This workspace does not currently expose any managed skills through the host runtime.';
  String get skillsManageSectionTitle =>
      isChinese ? '管理这个工作区中的技能' : 'Manage skills in this workspace';
  String get skillsInstallFromTitle => isChinese ? '安装来源' : 'Install from';
  String get skillsSuggestedTitle => isChinese ? '建议安装' : 'Suggested';
  String get skillsNoCatalogTitle =>
      isChinese ? '没有可安装技能' : 'No catalog skills';
  String get skillsNoCatalogBody => isChinese
      ? '这台设备上的本地目录里暂时没有额外可安装的技能。'
      : 'No additional skills are available from the local catalog on this device.';
  String get skillsLoadFailed => isChinese
      ? '从宿主运行时加载工作区技能失败。'
      : 'Failed to load workspace skills from the host runtime.';
  String skillsUpdateFailed(String name) =>
      isChinese ? '更新 $name 失败。' : 'Failed to update $name.';
  String skillsInstallFailed(String name) =>
      isChinese ? '安装 $name 失败。' : 'Failed to install $name.';
  String skillsPreviewFailed(String name) =>
      isChinese ? '加载 $name 的说明失败。' : 'Failed to load $name instructions.';
  String get skillsRefreshFailed =>
      isChinese ? '刷新技能失败。' : 'Failed to refresh skills.';
  String skillsRemoveFailed(String name) =>
      isChinese ? '移除 $name 失败。' : 'Failed to remove $name.';
  String get skillsPreviewInstructions =>
      isChinese ? '预览说明' : 'Preview instructions';
  String get skillsUpdateAction => isChinese ? '更新技能' : 'Update skills';
  String get skillsDisableForWorkspace =>
      isChinese ? '为这个工作区停用' : 'Disable for this workspace';
  String get skillsEnableForWorkspace =>
      isChinese ? '为这个工作区启用' : 'Enable for this workspace';
  String get skillsRemoveAction => isChinese ? '移除技能' : 'Remove skill';
  String get skillsClose => isChinese ? '关闭' : 'Close';
  String get skillsSummaryManageTitle => isChinese ? '工作区技能集' : 'Workspace set';
  String get skillsSummaryInstallTitle =>
      isChinese ? '可安装内容已准备好' : 'Install ready';
  String skillsSummaryManageBody(int enabledCount, int installedCount) =>
      isChinese
      ? '已启用 $enabledCount / $installedCount 个已安装技能'
      : '$enabledCount of $installedCount installed skills enabled';
  String skillsSummaryInstallBody(int suggestedCount) => isChinese
      ? '本地目录中有 $suggestedCount 个可用技能'
      : '$suggestedCount skills available from the local catalog';
  String get skillsManageTab => isChinese ? '管理' : 'Manage';
  String get skillsInstallTab => isChinese ? '安装' : 'Install';
  String get skillsInstallButton => isChinese ? '安装' : 'Install';
  String get skillsSearchHint =>
      isChinese ? '搜索技能目录' : 'Search the skill catalog';

  String get llmPageSubtitle => isChinese
      ? '选择提供商、路由和响应默认值。'
      : 'Select providers, routing, and response defaults.';
  String get llmPrimaryProviderTitle =>
      isChinese ? '主要提供商' : 'Primary provider';
  String get llmPrimaryProviderCustomHelper =>
      isChinese ? '适合需要配置较多提供商的场景。' : 'Best for larger provider lists.';
  String get llmPrimaryProviderPresetHelper => isChinese
      ? '适合已经配置好多个提供商的场景。'
      : 'Best when you have many providers configured.';
  String get llmProtocolTitle => isChinese ? 'API 协议' : 'API protocol';
  String llmOptionsCount(int count) =>
      isChinese ? '$count 项' : '$count options';
  String get llmProviderNameLabel => isChinese ? '提供商名称' : 'Provider name';
  String get llmProviderNameHint => 'Acme Inference';
  String get llmNotesLabel => isChinese ? '备注' : 'Notes';
  String get llmNotesHint => isChinese ? '区域回退' : 'Regional fallback';
  String get llmConnectionTitle => isChinese ? '连接' : 'Connection';
  String get llmValidating => isChinese ? '验证中…' : 'Validating…';
  String get llmSaving => isChinese ? '保存中…' : 'Saving…';
  String get llmValidateModel => isChinese ? '验证模型' : 'Validate Model';
  String get llmBaseUrlLabel => 'Base URL';
  String get llmBaseUrlHint => 'https://api.openai.com/v1';
  String get llmApiKeyLabel => 'API key';
  String get llmApiKeyHint =>
      isChinese ? '远程提供商需要填写' : 'Required for remote providers';
  String get llmStoredLocally => isChinese ? '已本地保存' : 'Stored locally';
  String get llmModelNameLabel => isChinese ? '模型名称' : 'Model name';
  String get llmModelHint => 'gpt-4o-mini';
  String get llmReasoningEffortLabel => isChinese ? '推理强度' : 'Reasoning effort';
  String get llmAnthropicThinkingEnabled =>
      isChinese ? '已启用 Anthropic thinking' : 'Anthropic thinking enabled';
  String get llmGptModelDetected =>
      isChinese ? '已检测到 GPT 模型' : 'GPT model detected';
  String get llmAdvancedPromptTitle => isChinese ? '高级提示词' : 'Advanced prompt';
  String get llmPromptOverrideLabel => isChinese ? '提示词覆盖' : 'Prompt override';
  String get llmPromptOverrideHint => isChinese
      ? '留空则使用默认 OpenCray 系统提示词'
      : 'Leave empty to use the default OpenCray system prompt';
  String get llmAutosaveHint => isChinese
      ? '字段失去焦点后会自动保存。'
      : 'Changes save automatically when a field loses focus.';
  String get llmValidateRequiresBaseUrl => isChinese
      ? '验证模型前必须填写 Base URL。'
      : 'Base URL is required to validate the model.';
  String get llmValidateRequiresModel => isChinese
      ? '验证模型前必须填写 Model。'
      : 'Model is required to validate the model.';
  String get llmFallbackCustomProviderTitle =>
      isChinese ? '自定义提供商' : 'Custom provider';
  String get llmFallbackCustomProviderSubtitle =>
      isChinese ? '当前没有可用的提供商预设。' : 'No provider presets are available.';
  String get llmProtocolAnthropic => 'Anthropic';
  String get llmProtocolOpenAiCompatible =>
      isChinese ? 'OpenAI 兼容' : 'OpenAI compatible';
  String llmReasoningTitle(String reasoningEffort) {
    if (!isChinese) {
      if (reasoningEffort == 'xhigh') {
        return 'XHigh';
      }
      return '${reasoningEffort[0].toUpperCase()}${reasoningEffort.substring(1)}';
    }
    switch (reasoningEffort) {
      case 'low':
        return '低';
      case 'high':
        return '高';
      case 'xhigh':
        return '超高';
      case 'medium':
      default:
        return '中';
    }
  }

  String get chatAddToMessage => isChinese ? '添加到消息' : 'Add to message';
  String get chatCommands => isChinese ? '命令' : 'Commands';
  String get chatSubmitFailed =>
      isChinese ? '向宿主运行时提交消息失败。' : 'Failed to submit message to host runtime.';
  String get chatPendingApprovalsTitle =>
      isChinese ? '待审批操作' : 'Pending approvals';
  String get chatHighRiskApproval => isChinese ? '高风险' : 'High risk';
  String get chatApprovalActionFailed =>
      isChinese ? '处理审批请求失败。' : 'Failed to process approval request.';
  String get chatComposerPlaceholder =>
      isChinese ? '给 OpenCray 发消息' : 'Message OpenCray';
  String get chatActionImage => isChinese ? '图片' : 'Image';
  String get chatActionFile => isChinese ? '文件' : 'File';
  String get chatActionCommand => isChinese ? '命令' : 'Command';
  String get chatCommandPatch => isChinese ? '补丁' : 'Patch';
  String get chatCommandReview => isChinese ? '审查' : 'Review';
  String get chatCommandActionDescription => isChinese
      ? '在批准后执行一个工作区 shell 命令。'
      : 'Run a workspace shell command with approval.';
  String get chatPatchActionDescription => isChinese
      ? '对当前仓库应用一个结构化文件补丁。'
      : 'Apply a structured file patch to the current repo.';
  String get chatReviewActionDescription => isChinese
      ? '扫描当前改动列表并总结风险。'
      : 'Scan the current change list and summarize risks.';
  String get chatToday => isChinese ? '今天' : 'Today';
  String get chatSeedDrawerEyebrow => isChinese ? '会话历史' : 'SESSION HISTORY';
  String get chatSeedRecentSessions => isChinese ? '最近会话' : 'Recent sessions';
  String get chatSeedNewSession => isChinese ? '新建会话' : 'New session';
  String get chatSeedRefineLayoutTitle =>
      isChinese ? '优化移动端布局' : 'Refine mobile layout';
  String get chatSeedRefineLayoutPreview => isChinese
      ? '安全模式下，这个会话中的编辑仍然会先询问。'
      : 'Safe mode still asks before edits in this session.';
  String get chatSeedNow => isChinese ? '刚刚' : 'Now';
  String get chatSeedReviewShellTitle =>
      isChinese ? '审查壳层限制' : 'Review shell limits';
  String get chatSeedReviewShellPreview =>
      isChinese ? '总结当前的权限边界。' : 'Summarize the current permission boundaries.';
  String get chatSeedMinutesAgo => isChinese ? '18 分钟前' : '18 min ago';
  String get chatSeedPrepareShellTitle =>
      isChinese ? '准备 Flutter 壳层' : 'Prepare Flutter shell';
  String get chatSeedPrepareShellPreview => isChinese
      ? '把迁移拆成宿主层和表现层。'
      : 'Split the migration into host and presentation layers.';
  String get chatSeedYesterday => isChinese ? '昨天' : 'Yesterday';
  String get chatSeedSummaryTitle =>
      isChinese ? '优化移动端布局' : 'Refine mobile layout';
  String get chatSeedSummaryBadge => isChinese ? '3 个待处理' : '3 pending';
  String get chatSeedSummaryBody => isChinese
      ? '安全模式下，这个会话中的编辑仍然会先询问。'
      : 'Safe mode still asks before edits in this session.';
  String get chatSeedWorkspaceReady => isChinese
      ? '工作区已就绪。我可以检查或编辑。'
      : 'Workspace ready. I can inspect or edit.';
  String get chatSeedWhyWritePending =>
      isChinese ? '为什么写入权限还在等待？' : 'Why is write access pending?';
  String get chatSeedSafeModeAsks =>
      isChinese ? '安全模式下，编辑仍然会先询问。' : 'Safe mode still asks before edits.';
  String get chatSeedShowCurrentLimits =>
      isChinese ? '展示当前限制。' : 'Show current limits.';
  String get chatSeedDropFileHint => isChinese
      ? '在下一条提示里拖入一张截图或一个工作区文件。'
      : 'Drop a screenshot or workspace file into the next prompt.';
  String get chatSeedUseTwoFiles =>
      isChinese ? '下一轮请使用这两个文件。' : 'Use these two files in the next pass.';
  String get chatSeedChooseCommand => isChinese
      ? '选择一个命令快捷方式，或者继续输入。'
      : 'Choose a command shortcut or keep typing.';
  String get chatSeedOpenWorkspaceCommand =>
      isChinese ? '为工作区根目录打开一个命令。' : 'Open a command for the workspace root.';
  String get chatSeedAddBeforeSending => isChinese
      ? '发送前先添加图片、文件或命令。'
      : 'Add an image, file, or command before sending.';
  String get chatSeedScreenTitle => isChinese ? '对话' : 'Chat';
  String get chatSeedEmptyTitle =>
      isChinese ? '开始一个新会话' : 'Start a new session';
  String get chatSeedEmptyBadge => isChinese ? '还没有历史' : 'No history yet';
  String get chatSeedEmptyBody => isChinese
      ? '给 OpenCray 发送任务、文件、图片或命令。'
      : 'Message OpenCray with a task, file, image, or command.';
  String get chatSeedAttachmentsTitle =>
      isChinese ? '检查已附加文件' : 'Review attached files';
  String get chatSeedAttachmentsBadge =>
      isChinese ? '2 个条目已准备好' : '2 items ready';
  String get chatSeedAttachmentsBody => isChinese
      ? '这些附件只会跟随下一条消息发送。'
      : 'These attachments stay with the next message only.';
  String get chatSeedCommandTitle => isChinese ? '命令快捷方式' : 'Command shortcuts';
  String get chatSeedCommandBadge => isChinese ? '安全模式' : 'Safe mode';
  String get chatSeedCommandBody => isChinese
      ? '在发送下一条提示前，先选择一个命令入口。'
      : 'Choose a command surface before sending the next prompt.';
  String get chatSeedAddMenuTitle =>
      isChinese ? '准备下一轮' : 'Prepare the next turn';
  String get chatSeedAddMenuBadge => isChinese ? '输入区已展开' : 'Composer open';
  String get chatSeedAddMenuBody => isChinese
      ? '发送下一次请求前先补充上下文。'
      : 'Add context before you send the next request.';
  String get chatSeedImageDetail =>
      isChinese ? '图片 · 1.8 MB' : 'Image · 1.8 MB';
  String get chatSeedFileDetail => isChinese ? '文件 · 12 KB' : 'File · 12 KB';
}
