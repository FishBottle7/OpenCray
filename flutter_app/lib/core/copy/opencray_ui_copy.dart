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
  String get appBackExitHint =>
      isChinese ? '再按一次返回即可退出' : 'Press back again to exit';
  String get contentLoadingLabel => isChinese ? '正在载入…' : 'Loading...';
  String get filesSearchHint =>
      isChinese ? '搜索文件和文件夹' : 'Search files and folders';
  String get filesSearchClearAction => isChinese ? '清空搜索' : 'Clear search';
  String filesFilteredStatus(String query, int count) => isChinese
      ? '正在筛选“$query” · 显示 $count 项'
      : 'Filtering "$query" · $count shown';
  String get filesSelectionStandardActions =>
      isChinese ? '文件操作' : 'File actions';
  String get filesSelectionDangerActions =>
      isChinese ? '危险操作' : 'Destructive actions';
  String get filesLocationTitle => isChinese ? '位置' : 'Location';
  String get filesNewAction => isChinese ? '新建' : 'New';
  String get filesDoneAction => isChinese ? '完成' : 'Done';
  String get filesShareAction => isChinese ? '分享' : 'Share';
  String get filesMoveAction => isChinese ? '移动' : 'Move';
  String get filesCopyAction => isChinese ? '复制' : 'Copy';
  String get filesPasteAction => isChinese ? '粘贴' : 'Paste';
  String get filesRenameAction => isChinese ? '重命名' : 'Rename';
  String get filesDeleteAction => isChinese ? '删除' : 'Delete';
  String get filesOperationPreparingCopy => isChinese ? '复制已准备' : 'Copy ready';
  String get filesOperationPreparingMove => isChinese ? '移动已准备' : 'Move ready';
  String get filesOperationPasting => isChinese ? '粘贴中' : 'Pasting';
  String get filesOperationDeleting => isChinese ? '删除中' : 'Deleting';
  String get filesOperationDone => isChinese ? '已完成' : 'Done';
  String get filesOperationFailed => isChinese ? '操作失败' : 'Operation failed';
  String get filesCancelAction => isChinese ? '取消' : 'Cancel';
  String get filesCreateAction => isChinese ? '创建' : 'Create';
  String get filesSaveAction => isChinese ? '保存' : 'Save';
  String get filesCreateEntryTitle => isChinese ? '新建' : 'New';
  String get filesCreateFolderTitle => isChinese ? '新建文件夹' : 'New Folder';
  String get filesRenameEntryTitle => isChinese ? '重命名' : 'Rename';
  String get filesNameFieldHint => isChinese ? '输入名称' : 'Enter a name';
  String get filesCreateUnsupportedType =>
      isChinese ? '暂不支持创建这种文件。' : 'This file type is not supported here yet.';
  String get filesPreviewCloseAction => isChinese ? '关闭预览' : 'Close preview';
  String get filesPreviewEmptyBody =>
      isChinese ? '这个文件是空的。' : 'This file is empty.';
  String get filesPreviewTruncatedNotice => isChinese
      ? '预览只显示了文件的前一部分内容。'
      : 'Preview shows only the beginning of this file.';
  String filesSelectedCount(int count) =>
      isChinese ? '已选 $count 项' : '$count Selected';
  String filesActionUnavailable(String action) =>
      isChinese ? '$action 还没接入。' : '$action is not wired yet.';
  String get filesLocationPath => 'OpenCray / src / main';
  String get filesLocationItemCount => isChinese ? '622 项' : '622 items';
  String get filesRefreshAction => isChinese ? '刷新' : 'Refresh';
  String get filesTreeTitle => isChinese ? '文件树' : 'File tree';
  String get filesEmptyTitle => isChinese ? '工作区还是空的' : 'Workspace is empty';
  String get filesEmptyBody => isChinese
      ? '当代理开始写入工作区后，这里会显示真实的目录树。'
      : 'The real workspace tree will appear here once the agent starts writing files.';
  String get filesLoadFailed => isChinese
      ? '从宿主运行时加载文件树失败。'
      : 'Failed to load the workspace tree from the host runtime.';
  String get filesFolderEmptyTitle =>
      isChinese ? '这个文件夹还是空的' : 'This folder is empty';
  String get filesFolderEmptyBody => isChinese
      ? '当前目录里还没有文件或文件夹。'
      : 'There are no files or folders in this directory yet.';
  String get filesNoMatchesTitle => isChinese ? '没有匹配项' : 'No matching files';
  String filesNoMatchesBody(String query) => isChinese
      ? '当前工作区里没有匹配“$query”的文件或文件夹。'
      : 'No files or folders in the current workspace match "$query".';
  String filesDeleteConfirmTitle(int count) =>
      isChinese ? '删除这 $count 项？' : 'Delete these $count items?';
  String filesDeleteConfirmBody(int count) =>
      isChinese ? '删除后无法恢复。' : 'This action cannot be undone.';
  String filesWorkspaceTotals(int directoryCount, int fileCount) => isChinese
      ? '$directoryCount 个文件夹 · $fileCount 个文件'
      : '$directoryCount folders · $fileCount files';
  String filesItemsShown(int count) =>
      isChinese ? '显示 $count 项' : '$count shown';
  String get filesTreeTruncated => isChinese
      ? '文件树已按安全上限截断，只显示部分节点。'
      : 'The tree was truncated at a safety limit and only shows part of the workspace.';
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
      ? '搜索可安装技能，或直接输入 owner/repo、URL、source ref、本地路径进行安装。'
      : 'Search installable skills or enter an owner/repo, URL, source ref, or local path to install directly.';
  String get skillsNoInstalledTitle =>
      isChinese ? '还没有已安装技能' : 'No installed skills';
  String get skillsNoInstalledBody => isChinese
      ? '当前宿主运行时还没有向这个工作区暴露任何可管理技能。'
      : 'This workspace does not currently expose any managed skills through the host runtime.';
  String get skillsManageSectionTitle =>
      isChinese ? '管理这个工作区中的技能' : 'Manage skills in this workspace';
  String get skillsInstallFromTitle => isChinese ? '安装来源' : 'Install from';
  String get skillsSuggestedTitle => isChinese ? '建议安装' : 'Suggested';
  String get skillsResultsTitle => isChinese ? '搜索结果' : 'Results';
  String get skillsNoCatalogTitle =>
      isChinese ? '没有可安装技能' : 'No catalog skills';
  String get skillsNoCatalogBody => isChinese
      ? '这台设备上的本地目录里暂时没有额外可安装的技能。'
      : 'No additional skills are available from the local catalog on this device.';
  String get skillsNoResultsTitle =>
      isChinese ? '没有匹配结果' : 'No matching skills';
  String skillsNoResultsBody(String query) => isChinese
      ? '没有找到和“$query”匹配的技能。你仍然可以直接按这个来源安装。'
      : 'No skills matched "$query" yet. You can still install directly from this source.';
  String get skillsLoadFailed => isChinese
      ? '从宿主运行时加载工作区技能失败。'
      : 'Failed to load workspace skills from the host runtime.';
  String skillsUpdateFailed(String name) =>
      isChinese ? '更新 $name 失败。' : 'Failed to update $name.';
  String skillsInstallFailed(String name) =>
      isChinese ? '安装 $name 失败。' : 'Failed to install $name.';
  String skillsPreviewFailed(String name) =>
      isChinese ? '加载 $name 的说明失败。' : 'Failed to load $name instructions.';
  String skillsSuggestedPreviewFailed(String name) =>
      isChinese ? '加载 $name 的技能内容失败。' : 'Failed to load $name skill contents.';
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
      ? '当前有 $suggestedCount 个可安装结果'
      : '$suggestedCount installable results ready';
  String get skillsManageTab => isChinese ? '管理' : 'Manage';
  String get skillsInstallTab => isChinese ? '安装' : 'Install';
  String get skillsInstallButton => isChinese ? '安装' : 'Install';
  String get skillsInstallingButton => isChinese ? '安装中' : 'Installing';
  String get skillsInstalledButton => isChinese ? '已安装' : 'Installed';
  String get skillsRetryInstallButton => isChinese ? '重试' : 'Retry';
  String get skillsUpdatingButton => isChinese ? '更新中' : 'Updating';
  String get skillsUpdatedButton => isChinese ? '已更新' : 'Updated';
  String get skillsDeletingButton => isChinese ? '删除中' : 'Deleting';
  String get skillsDeletedButton => isChinese ? '已删除' : 'Deleted';
  String get skillsEnablingButton => isChinese ? '启用中' : 'Enabling';
  String get skillsDisablingButton => isChinese ? '停用中' : 'Disabling';
  String get skillsEnabledButton => isChinese ? '已启用' : 'Enabled';
  String get skillsDisabledButton => isChinese ? '已停用' : 'Disabled';
  String get skillsActionFailedButton => isChinese ? '操作失败' : 'Action failed';
  String get skillsPreviewButton => isChinese ? '查看内容' : 'Preview';
  String get skillsInspectButton => isChinese ? '检查' : 'Inspect';
  String get skillsCancelAction => isChinese ? '取消' : 'Cancel';
  String get skillsSearchHint => isChinese
      ? '搜索技能，或输入 owner/repo、URL、本地路径'
      : 'Search skills or enter owner/repo, URL, or a local path';
  String get skillsDirectInstallTitle =>
      isChinese ? '直接按来源安装' : 'Install from source';
  String skillsDirectInstallBody(String sourceRef) => isChinese
      ? '将“$sourceRef”直接交给 SkillsAdd 安装。'
      : 'Send "$sourceRef" straight to SkillsAdd for installation.';
  String get skillsShowMoreResults => isChinese ? '显示更多结果' : 'Show more';
  String skillsInstallsCount(int installs) =>
      isChinese ? '$installs 次安装' : '$installs installs';
  String skillsInspectSourceTitle(String sourceTitle) =>
      isChinese ? '检查$sourceTitle' : 'Inspect $sourceTitle';
  String skillsSourceInputLabel(String sourceTitle) =>
      isChinese ? '$sourceTitle 来源' : '$sourceTitle source';
  String skillsSourceInputHint(String sourceId) {
    switch (sourceId) {
      case 'local-path':
        return isChinese
            ? '/storage/emulated/0/Download/skills'
            : '/storage/emulated/0/Download/skills';
      case 'github-url':
        return isChinese
            ? 'https://github.com/owner/repo 或 owner/repo'
            : 'https://github.com/owner/repo or owner/repo';
      case 'gitlab-url':
        return isChinese
            ? 'https://gitlab.com/group/project/repo 或 gitlab:group/project/repo'
            : 'https://gitlab.com/group/project/repo or gitlab:group/project/repo';
      default:
        return isChinese ? '输入技能来源' : 'Enter a skill source';
    }
  }

  String skillsInspectFailed(String sourceRef) =>
      isChinese ? '检查 $sourceRef 失败。' : 'Failed to inspect $sourceRef.';
  String skillsNoInstallableSkills(String sourceRef) => isChinese
      ? '$sourceRef 里没有找到可安装技能。'
      : 'No installable skills were found in $sourceRef.';
  String skillsSelectSkillsTitle(String sourceRef) =>
      isChinese ? '选择要安装的技能' : 'Choose skills to install';
  String skillsSelectSkillsBody(String sourceRef) => isChinese
      ? '$sourceRef 中包含多个技能，请选择要安装的项。'
      : '$sourceRef contains multiple skills. Choose what to install.';
  String get skillsSelectAllAction => isChinese ? '全选' : 'Select all';
  String get skillsClearSelectionAction => isChinese ? '清空' : 'Clear';
  String skillsInstallSelectedAction(int count) =>
      isChinese ? '安装所选 ($count)' : 'Install selected ($count)';
  String skillsInstallBatchSummary(int installedCount, int totalCount) =>
      isChinese
      ? '已安装 $installedCount / $totalCount 个技能。'
      : 'Installed $installedCount of $totalCount skills.';

  String get llmPageSubtitle => isChinese
      ? '选择 OpenCray 运行语言模型的位置。'
      : 'Choose where OpenCray runs language models.';
  String get llmModelSourceTitle => isChinese ? '模型来源' : 'Model source';
  String get llmSourceCloudLabel => isChinese ? '云端' : 'Cloud';
  String get llmSourceOnDeviceLabel => isChinese ? '端侧' : 'On-device';
  String get llmPrimaryProviderTitle =>
      isChinese ? '主要提供商' : 'Primary provider';
  String get llmPrimaryProviderCustomHelper =>
      isChinese ? '适合需要配置较多提供商的场景。' : 'Best for larger provider lists.';
  String get llmPrimaryProviderPresetHelper => isChinese
      ? '适合已经配置好多个提供商的场景。'
      : 'Best when you have many providers configured.';
  String get llmSaveProviderAction => isChinese ? '保存' : 'Save';
  String get llmSaveProviderSuccess =>
      isChinese ? '已保存到主要提供商列表。' : 'Saved to the primary provider list.';
  String get llmSaveProviderTemporary => isChinese ? '临时改动' : 'Temp edit';
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
  String get llmApiKeyLabel => isChinese ? 'API 密钥' : 'API key';
  String get llmApiKeyHint =>
      isChinese ? '远程提供商需要填写' : 'Required for remote providers';
  String get llmStoredLocally => isChinese ? '已本地保存' : 'Stored locally';
  String get llmModelNameLabel => isChinese ? '模型名称' : 'Model name';
  String get llmModelHint => 'gpt-4o-mini';
  String get llmReasoningEffortLabel => isChinese ? '推理强度' : 'Reasoning effort';
  String get llmThinkingLabel => isChinese ? '思考强度' : 'Thinking';
  String get llmStreamingTitle =>
      isChinese ? '聊天回答流式显示' : 'Stream chat replies';
  String get llmStreamingSubtitle => isChinese
      ? '开启后，assistant 回复会在聊天气泡里边生成边显示；关闭后恢复一次性显示最终结果。'
      : 'When enabled, assistant replies grow inside the chat bubble. When disabled, only the final reply appears.';
  String get llmAnthropicThinkingEnabled =>
      isChinese ? '已启用 Anthropic thinking' : 'Anthropic thinking enabled';
  String get llmAnthropicThinkingDisabled =>
      isChinese ? '已关闭 Anthropic thinking' : 'Anthropic thinking disabled';
  String get llmGptModelDetected =>
      isChinese ? '已检测到 GPT 模型' : 'GPT model detected';
  String get llmPromptCacheTitle => isChinese ? '提示缓存' : 'Prompt cache';
  String get llmOnDeviceModelTitle => isChinese ? '端侧模型' : 'On-device model';
  String get llmSamplingLimitsTitle =>
      isChinese ? '采样与限制' : 'Sampling & limits';
  String get llmRuntimeTitle => isChinese ? '运行时' : 'Runtime';
  String get llmOnDeviceLiteModeTitle =>
      isChinese ? '端侧轻量模式' : 'On-device lite mode';
  String get llmOnDeviceLiteModeBody => isChinese
      ? '优先本地回复速度和温控。开启后会关闭工具、记忆召回和人格注入，并压缩提示词预算。'
      : 'Prioritize on-device speed and thermals. When enabled, OpenCray disables tools, memory recall, and soul injection, and uses a tighter prompt budget.';
  String get llmMaxContextWindowLabel =>
      isChinese ? '最大上下文窗口' : 'Max context window';
  String get llmMaxTokensLabel => isChinese ? '最大输出 token' : 'Max tokens';
  String get llmTopKLabel => 'Top K';
  String get llmTopPLabel => 'Top P';
  String get llmTemperatureLabel => isChinese ? '温度' : 'Temperature';
  String get llmAcceleratorLabel => isChinese ? '加速器' : 'Accelerator';
  String get llmAcceleratorGpu => 'GPU';
  String get llmAcceleratorCpu => 'CPU';
  String get llmThinkingOff => isChinese ? 'Off' : 'Off';
  String get llmThinkingOn => isChinese ? 'On' : 'On';
  String get llmSelectedChip => isChinese ? '已选中' : 'Selected';
  String get llmUseModelChip => isChinese ? '使用' : 'Use';
  String get llmDownloadModelChip => isChinese ? '下载' : 'Download';
  String get llmDownloadingChip => isChinese ? '下载中' : 'Downloading';
  String get llmPreparingChip => isChinese ? '校验中' : 'Preparing';
  String get llmRetryModelChip => isChinese ? '重试' : 'Retry';
  String get llmCancelChip => isChinese ? '取消' : 'Cancel';
  String get llmDeleteChip => isChinese ? '删除' : 'Delete';
  String llmInstalledStatus(String sizeLabel) =>
      isChinese ? '已安装 · $sizeLabel' : 'Installed · $sizeLabel';
  String llmNotDownloadedStatus(String sizeLabel) =>
      isChinese ? '未下载 · $sizeLabel' : 'Not downloaded · $sizeLabel';
  String llmDownloadingStatus(
    String progressLabel,
    String sizeLabel, {
    String? speedLabel,
  }) {
    final text = isChinese
        ? '下载中 · $progressLabel / $sizeLabel'
        : 'Downloading · $progressLabel / $sizeLabel';
    if (speedLabel == null || speedLabel.trim().isEmpty) {
      return text;
    }
    return '$text · $speedLabel';
  }

  String llmVerifyingStatus(String sizeLabel) =>
      isChinese ? '校验中 · $sizeLabel' : 'Verifying · $sizeLabel';
  String llmFailedStatus(String message) => isChinese
      ? '失败 · ${message.isEmpty ? '请重试' : message}'
      : 'Failed · ${message.isEmpty ? 'Retry required' : message}';
  String llmModelDownloadStarted(String title) =>
      isChinese ? '开始下载 $title。' : 'Started downloading $title.';
  String llmModelDownloadCancelled(String title) =>
      isChinese ? '已取消 $title 下载。' : 'Cancelled $title download.';
  String llmModelDeleted(String title) =>
      isChinese ? '已删除 $title。' : 'Deleted $title.';
  String get llmPromptCacheOpenAiHelper => isChinese
      ? '控制 OpenAI 路由的 prompt cache hints。仅在目标路由支持时发送。'
      : 'Controls OpenAI prompt cache hints. They are only sent when the target route supports them.';
  String get llmPromptCacheAnthropicHelper => isChinese
      ? '控制 Anthropic 的 cache_control 提示块缓存。'
      : 'Controls Anthropic cache_control prompt caching.';
  String get llmOpenAiPromptCacheKeyLabel =>
      isChinese ? '缓存键范围' : 'Cache key scope';
  String get llmOpenAiPromptCacheRetentionLabel =>
      isChinese ? '缓存保留期' : 'Cache retention';
  String get llmAnthropicPromptCachingTitle =>
      isChinese ? '启用缓存' : 'Enable cache';
  String get llmAnthropicPromptCachingSubtitle => isChinese
      ? '为可复用提示块发送 Anthropic cache_control。'
      : 'Send Anthropic cache_control for reusable prompt blocks.';
  String get llmAnthropicPromptCacheTtlLabel =>
      isChinese ? '缓存 TTL' : 'Cache TTL';
  String get llmContextBudgetTitle => isChinese ? '上下文预算' : 'Context budget';
  String get llmContextBudgetPresetLabel =>
      isChinese ? '预算档位' : 'Budget preset';
  String get llmContextBudgetHelper => isChinese
      ? '这里只调整模型可见的上下文窗口假设。更大的窗口当前主要会降低压缩压力，还不会自动扩大记忆、转录或技能采集上限。'
      : 'This only adjusts the model-visible context-window assumption. Larger windows currently reduce compaction pressure but do not yet expand memory, transcript, or skill acquisition caps automatically.';
  String get llmContextBudgetPresetAuto => isChinese ? '自动' : 'Automatic';
  String get llmContextBudgetPresetDev => isChinese ? '开发者' : 'Dev';
  String get llmContextBudgetEditRawAction =>
      isChinese ? '编辑原始值' : 'Edit raw value';
  String llmContextBudgetResolved(String label) => isChinese
      ? '当前解析值：$label'
      : 'Resolved now: $label';
  String llmContextBudgetOverride(String label) => isChinese
      ? '手动覆盖：$label'
      : 'Manual override: $label';
  String get llmContextBudgetRawTitle =>
      isChinese ? '原始上下文窗口' : 'Raw context window';
  String get llmContextBudgetRawHint =>
      isChinese ? '例如 262144' : 'For example 262144';
  String get llmContextBudgetRawHelper => isChinese
      ? '留空会恢复自动判断。填写后会按当前路由保存为手动覆盖。'
      : 'Leave this empty to return to automatic detection. When set, the value is stored as a manual override for the current route.';
  String get llmContextBudgetRawApply => isChinese ? '应用' : 'Apply';
  String get llmContextBudgetResetAuto =>
      isChinese ? '恢复自动' : 'Reset to auto';
  String get llmContextBudgetInvalid =>
      isChinese ? '请输入大于 0 的整数。' : 'Enter an integer greater than 0.';
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
  String get llmValidateRequiresModel =>
      isChinese ? '验证模型前必须填写模型名称。' : 'Model is required to validate the model.';
  String get llmFallbackCustomProviderTitle =>
      isChinese ? '自定义提供商' : 'Custom provider';
  String get llmFallbackCustomProviderSubtitle =>
      isChinese ? '当前没有可用的提供商预设。' : 'No provider presets are available.';
  String get llmProtocolAnthropic => 'Anthropic';
  String get llmProtocolOpenAiResponses => 'OpenAI Responses';
  String get llmProtocolOpenAiCompatible =>
      isChinese ? 'OpenAI 兼容' : 'OpenAI compatible';
  String networkSearchSlotTitle(int index) =>
      isChinese ? '槽位 ${index + 1}' : 'Slot ${index + 1}';
  String get networkSearchProviderLabel => isChinese ? '提供商' : 'Provider';
  String get networkSearchLabelFieldTitle => isChinese ? '标签' : 'Label';
  String get networkSearchLabelHint =>
      isChinese ? '例如：主 Exa' : 'For example: Primary Exa';
  String get networkSearchBaseUrlFieldTitle =>
      isChinese ? 'Base URL' : 'Base URL';
  String get networkSearchBaseUrlHint => isChinese
      ? 'OpenAI 搜索 endpoint，例如：https://api.openai.com/v1'
      : 'OpenAI search endpoint, for example: https://api.openai.com/v1';
  String get networkSearchModelFieldTitle => isChinese ? '模型' : 'Model';
  String get networkSearchModelHint =>
      isChinese ? '例如：gpt-5' : 'For example: gpt-5';
  String get networkSearchApiKeyFieldTitle => isChinese ? 'API 密钥' : 'API key';
  String get networkSearchApiKeyHint =>
      isChinese ? '粘贴 provider 的密钥' : 'Paste the provider key';
  String get networkSearchAddSlotAction =>
      isChinese ? '+ 添加搜索槽位' : '+ Add search slot';
  String get networkSearchMoveUp => isChinese ? '上移' : 'Move up';
  String get networkSearchMoveDown => isChinese ? '下移' : 'Move down';
  String get networkSearchDelete => isChinese ? '删除' : 'Delete';
  String get networkSearchSaving => isChinese ? '保存中…' : 'Saving…';
  String networkSearchProviderTitle(String providerId) {
    switch (providerId) {
      case 'tavily':
        return 'TAVILY';
      case 'brave':
        return 'BRAVE';
      case 'openai_web_search':
        return 'OPENAI';
      case 'exa':
      default:
        return 'EXA';
    }
  }

  String llmReasoningTitle(String reasoningEffort) {
    if (!isChinese) {
      if (reasoningEffort == 'off') {
        return 'Off';
      }
      if (reasoningEffort == 'xhigh') {
        return 'XHigh';
      }
      return '${reasoningEffort[0].toUpperCase()}${reasoningEffort.substring(1)}';
    }
    switch (reasoningEffort) {
      case 'off':
        return '关闭';
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

  String llmOpenAiPromptCacheKeyStrategyTitle(String strategy) {
    if (!isChinese) {
      switch (strategy) {
        case 'route':
          return 'Per route';
        case 'session':
          return 'Per session';
        case 'none':
        default:
          return 'Disabled';
      }
    }
    switch (strategy) {
      case 'route':
        return '按路由';
      case 'session':
        return '按会话';
      case 'none':
      default:
        return '关闭';
    }
  }

  String llmOpenAiPromptCacheRetentionTitle(String retention) {
    if (!isChinese) {
      switch (retention) {
        case 'in_memory':
          return 'In memory';
        case '24h':
          return '24 hours';
        case '':
        default:
          return 'Default';
      }
    }
    switch (retention) {
      case 'in_memory':
        return '仅内存';
      case '24h':
        return '24 小时';
      case '':
      default:
        return '默认';
    }
  }

  String llmAnthropicPromptCacheTtlTitle(String ttl) {
    if (!isChinese) {
      switch (ttl) {
        case '1h':
          return '1 hour';
        case '5m':
        default:
          return '5 minutes';
      }
    }
    switch (ttl) {
      case '1h':
        return '1 小时';
      case '5m':
      default:
        return '5 分钟';
    }
  }

  String get chatAddToMessage => isChinese ? '添加到消息' : 'Add to message';
  String get chatCommands => isChinese ? '命令' : 'Commands';
  String get chatSubmitFailed =>
      isChinese ? '向宿主运行时提交消息失败。' : 'Failed to submit message to host runtime.';
  String get chatMessageCopyAction => isChinese ? '复制' : 'Copy';
  String get chatMessageRecallAction => isChinese ? '撤回' : 'Undo';
  String get chatMessageRedoAction => isChinese ? '重做' : 'Redo';
  String get chatMessageEditAction => isChinese ? '编辑' : 'Edit';
  String get chatMessageBranchAction => isChinese ? '分支' : 'Branch';
  String get chatMessageDeleteAction => isChinese ? '删除' : 'Delete';
  String get chatMessageSelectAction => isChinese ? '多选' : 'Select';
  String get chatMessageQuoteAction => isChinese ? '引用' : 'Quote';
  String get chatMessageCopied => isChinese ? '已复制消息。' : 'Message copied.';
  String chatAutomationModeSemanticLabel(String modeLabel) =>
      isChinese ? '自动化模式：$modeLabel' : 'Automation mode: $modeLabel';
  String get chatSelectionDoneAction => isChinese ? '完成' : 'Done';
  String chatSelectionCount(int count) =>
      isChinese ? '已选 $count 条' : '$count Selected';
  String get chatSelectionCopied =>
      isChinese ? '已复制所选消息。' : 'Copied the selected messages.';
  String get chatMessageQuoted =>
      isChinese ? '已添加引用到输入框。' : 'Added quote to the composer.';
  String get chatMessageActionFailed =>
      isChinese ? '处理消息操作失败。' : 'Failed to process the message action.';
  String get chatAttachmentShareAction => isChinese ? '分享' : 'Share';
  String get chatAttachmentSaveAction => isChinese ? '保存' : 'Save';
  String get chatAttachmentSavedToDownloads =>
      isChinese ? '已保存到下载目录。' : 'Saved to Downloads.';
  String get chatAttachmentSavedToRecordings =>
      isChinese ? '已保存到录音目录。' : 'Saved to Recordings.';
  String get chatAttachmentShareFailed =>
      isChinese ? '分享附件失败。' : 'Failed to share the attachment.';
  String get chatAttachmentSaveFailed =>
      isChinese ? '保存附件失败。' : 'Failed to save the attachment.';
  String get markdownLinkUnsupported =>
      isChinese ? '暂不支持这种链接。' : 'This link target is not supported.';
  String get markdownLinkHttpOnly => isChinese
      ? '只支持 http 或 https 链接。'
      : 'Only http and https links are supported.';
  String get markdownLinkNoAppAvailable =>
      isChinese ? '没有可用的应用可以打开这个链接。' : 'No application can open this link.';
  String get markdownLinkOpenFailed =>
      isChinese ? '打开外部链接失败。' : 'Failed to open the external link.';
  String get markdownLinkExternalUnavailable =>
      isChinese ? '当前无法打开外部链接。' : 'External links are unavailable right now.';
  String get markdownLinkHostUnavailable =>
      isChinese ? '当前无法处理这个链接。' : 'Link handling is temporarily unavailable.';
  String get chatMessageMultiSelectPending =>
      isChinese ? '多选模式还没接入。' : 'Multi-select mode is not wired yet.';
  String get chatPendingApprovalsTitle =>
      isChinese ? '待审批操作' : 'Pending approvals';
  String get chatHighRiskApproval => isChinese ? '高风险' : 'High risk';
  String get chatApprovalToolLabel => isChinese ? '工具' : 'Tool';
  String get chatApprovalRequestLabel => isChinese ? '申请内容' : 'Request';
  String get chatApprovalDetailsLabel => isChinese ? '细节' : 'Details';
  String get chatApprovalPathsLabel => isChinese ? '目标路径' : 'Paths';
  String get chatApprovalWorkingDirectoryLabel =>
      isChinese ? '工作目录' : 'Working directory';
  String get chatApprovalReasonLabel => isChinese ? '理由' : 'Agent reason';
  String get chatApprovalDecisionApproved => isChinese ? '已批准' : 'Approved';
  String get chatApprovalDecisionApprovedForSession =>
      isChinese ? '本会话已批准' : 'Approved for session';
  String get chatApprovalBatchAction =>
      isChinese ? '本次及同类命令都批准' : 'Approve this and similar commands';
  String get chatApprovalDecisionRejected => isChinese ? '已拒绝' : 'Rejected';
  String get chatApprovalActionFailed =>
      isChinese ? '处理审批请求失败。' : 'Failed to process approval request.';
  String get chatSessionActionFailed =>
      isChinese ? '处理会话操作失败。' : 'Failed to process the session action.';
  String get chatRunWorkingLabel => isChinese ? '运行中' : 'Running';
  String get chatRunWaitingApprovalLabel =>
      isChinese ? '等待审批' : 'Waiting for approval';
  String get chatRunAwaitingDirectionLabel =>
      isChinese ? '等待指示' : 'Awaiting direction';
  String get chatRunResumeAction => isChinese ? '继续运行' : 'Resume run';
  String get chatRunLlmRetryPausedBody => isChinese
      ? '语言模型重试次数已耗尽，这次运行已暂停。发送下一条消息或点击继续运行后，会从当前检查点恢复。'
      : 'LLM retries were exhausted, so this run is paused. Send another message or tap Resume run to continue from the current checkpoint.';
  String get chatRunApprovalDecisionDeferredBody => isChinese
      ? '审批决定已经记录。发送下一条消息或点击继续运行后，才会把这个决定应用到当前运行。'
      : 'The approval decision has been recorded. Send another message or tap Resume run to apply it to this run.';
  String get chatRunInterruptAction => isChinese ? '中断' : 'Interrupt';
  String get chatRunInterruptBusy => isChinese ? '正在中断…' : 'Interrupting…';
  String get chatRunInterruptConfirmLabel =>
      isChinese ? '左滑以中断任务' : 'Slide left to interrupt';
  String get chatRunInterruptFailed =>
      isChinese ? '无法中断这次运行。' : 'Unable to interrupt this run.';
  String get chatRunPreviewTitle => isChinese ? '沙盒预览' : 'Sandbox Preview';
  String get chatRunPreviewOpenAction => 'Open';
  String get chatRunPreviewCopyUrlAction => isChinese ? '复制链接' : 'Copy URL';
  String get chatRunPreviewCopied =>
      isChinese ? '已复制预览链接。' : 'Preview URL copied.';
  String get chatRunPreviewEmbedLoading =>
      isChinese ? '正在载入内嵌预览…' : 'Loading embedded preview...';
  String get chatRunPreviewEmbedUnavailable =>
      isChinese ? '当前无法显示内嵌预览。' : 'Embedded preview is unavailable.';
  String get chatRunPreviewEmbedUnsupported => isChinese
      ? '当前宿主环境不支持内嵌网页预览。'
      : 'This host does not support embedded web previews.';
  String get chatRunPreviewStatusReady => isChinese ? '已就绪' : 'Ready';
  String get chatRunPreviewStatusReachable => isChinese ? '可达' : 'Reachable';
  String get chatRunPreviewStatusUnreachable =>
      isChinese ? '未就绪' : 'Unreachable';
  String get chatRunPreviewStatusSkipped => isChinese ? '未探测' : 'Skipped';
  String get chatRunSandboxSessionTitle => isChinese ? '云端会话' : 'Cloud Session';
  String get chatRunSandboxSessionMissing => isChinese
      ? '当前工作区还没有可复用的云端沙盒会话。'
      : 'No reusable cloud sandbox session is recorded for this workspace.';
  String get chatRunSandboxSessionSourceActive => isChinese ? '活动中' : 'Active';
  String get chatRunSandboxSessionSourcePersisted =>
      isChinese ? '已保存' : 'Saved';
  String get chatRunSandboxSessionSourceActiveAndPersisted =>
      isChinese ? '活动 + 已保存' : 'Active + Saved';
  String get chatRunSandboxSessionSourceNone =>
      isChinese ? '无会话' : 'No session';
  String get chatRunSandboxSessionLifecycleActive =>
      isChinese ? '正常' : 'Healthy';
  String get chatRunSandboxSessionLifecycleStale => isChinese ? '陈旧' : 'Stale';
  String get chatRunSandboxSessionLifecycleReclaimed =>
      isChinese ? '已回收' : 'Reclaimed';
  String get chatRunSandboxSessionLifecycleNone =>
      isChinese ? '无会话' : 'No session';
  String chatRunSandboxSessionPorts(String ports) =>
      isChinese ? '端口 $ports' : 'Ports $ports';
  String chatRunSandboxSessionTemplate(String templateId) =>
      isChinese ? '模板 $templateId' : 'Template $templateId';
  String chatRunSandboxSessionRunningCount(int count) =>
      isChinese ? '运行中 $count' : 'Running $count';
  String chatRunSandboxSessionUpdated(String label) =>
      isChinese ? '更新于 $label' : 'Updated $label';
  String chatRunSandboxSessionLastActive(String label) =>
      isChinese ? '最近活跃于 $label' : 'Last active $label';
  String chatRunSandboxSessionStaleAfter(String label) =>
      isChinese ? '预计在 $label 判定陈旧' : 'Stales after $label';
  String chatRunSandboxSessionPreviewChecked(String label) =>
      isChinese ? '预览探测于 $label' : 'Preview checked $label';
  String chatRunSandboxSessionPreviewStatus(String label) =>
      isChinese ? '预览 $label' : 'Preview $label';
  String get chatRunSandboxSessionRunningRequestsTitle =>
      isChinese ? '运行中的请求' : 'Running requests';
  String get chatRunThinkingActive => isChinese
      ? '正在分析请求，并决定下一步要做什么。'
      : 'Analyzing the request and deciding the next step.';
  String chatRunCallingTool(String toolName) =>
      isChinese ? '正在调用工具：$toolName' : 'Calling tool: $toolName';
  String chatRunToolFollowUp(String toolName) => isChinese
      ? '已收到 $toolName 的结果，正在判断下一步。'
      : 'Received $toolName and evaluating the next step.';
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
  String get chatYesterday => isChinese ? '昨天' : 'Yesterday';
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
  String get chatSeedYesterday => chatYesterday;
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
