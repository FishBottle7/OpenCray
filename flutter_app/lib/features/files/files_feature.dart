import 'package:flutter/material.dart';

import '../../core/copy/opencray_ui_copy.dart';

class FileDirectoryData {
  const FileDirectoryData({required this.name, required this.itemCount});

  final String name;
  final String itemCount;
}

class FileEntryData {
  const FileEntryData({required this.name, required this.meta});

  final String name;
  final String meta;
}

class FilesFeatureScreen extends StatelessWidget {
  const FilesFeatureScreen({super.key, required this.copy});

  final OpenCrayUiCopy copy;

  static const _shellBackground = Color(0xFFF5F5F7);
  static const _textPrimary = Color(0xFF111111);
  static const _textSecondary = Color(0xFF6E6E73);
  static const _textTertiary = Color(0xFF8E8E93);
  static const _accent = Color(0xFF007AFF);
  static const _divider = Color(0xFFE5E5EA);

  @override
  Widget build(BuildContext context) {
    final directories = <FileDirectoryData>[
      FileDirectoryData(
        name: 'ui',
        itemCount: copy.filesDirectoryItemCount(12),
      ),
      FileDirectoryData(
        name: 'app',
        itemCount: copy.filesDirectoryItemCount(8),
      ),
      FileDirectoryData(
        name: 'docs',
        itemCount: copy.filesDirectoryItemCount(19),
      ),
    ];
    final entries = <FileEntryData>[
      FileEntryData(
        name: 'chat_feature_screen.dart',
        meta: copy.filesEntryMetaChat,
      ),
      FileEntryData(name: 'SkillsScreen.kt', meta: copy.filesEntryMetaSkills),
      FileEntryData(
        name: 'mobile-ui-layout-spec.md',
        meta: copy.filesEntryMetaSpec,
      ),
    ];
    return ColoredBox(
      color: _shellBackground,
      child: SafeArea(
        bottom: false,
        child: SingleChildScrollView(
          padding: const EdgeInsets.fromLTRB(20, 8, 20, 24),
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.stretch,
            children: [
              Text(
                copy.filesTitle,
                style: const TextStyle(
                  fontSize: 30,
                  height: 1.05,
                  fontWeight: FontWeight.w600,
                  color: _textPrimary,
                ),
              ),
              const SizedBox(height: 16),
              _SearchBar(hint: copy.filesSearchHint),
              const SizedBox(height: 12),
              _LocationCard(
                title: copy.filesLocationTitle,
                path: copy.filesLocationPath,
                itemCount: copy.filesLocationItemCount,
                availableSpace: copy.filesLocationAvailableSpace,
              ),
              const SizedBox(height: 12),
              _FileListCard(directories: directories, entries: entries),
            ],
          ),
        ),
      ),
    );
  }
}

class _SearchBar extends StatelessWidget {
  const _SearchBar({required this.hint});

  final String hint;

  @override
  Widget build(BuildContext context) {
    return DecoratedBox(
      decoration: BoxDecoration(
        color: Colors.white,
        borderRadius: BorderRadius.circular(12),
      ),
      child: Padding(
        padding: EdgeInsets.symmetric(horizontal: 12, vertical: 12),
        child: Row(
          children: [
            const Icon(
              Icons.search_rounded,
              size: 18,
              color: Color(0xFF8E8E93),
            ),
            const SizedBox(width: 10),
            Expanded(
              child: Text(
                hint,
                style: const TextStyle(
                  fontSize: 14,
                  height: 1.2,
                  color: Color(0xFF8E8E93),
                ),
              ),
            ),
          ],
        ),
      ),
    );
  }
}

class _LocationCard extends StatelessWidget {
  const _LocationCard({
    required this.title,
    required this.path,
    required this.itemCount,
    required this.availableSpace,
  });

  final String title;
  final String path;
  final String itemCount;
  final String availableSpace;

  @override
  Widget build(BuildContext context) {
    return DecoratedBox(
      decoration: BoxDecoration(
        color: Colors.white,
        borderRadius: BorderRadius.circular(16),
      ),
      child: Padding(
        padding: const EdgeInsets.all(12),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Text(
              title,
              style: TextStyle(
                fontSize: 12,
                height: 1.2,
                color: Color(0xFF8E8E93),
              ),
            ),
            const SizedBox(height: 6),
            Text(
              path,
              style: TextStyle(
                fontSize: 17,
                height: 1.2,
                fontWeight: FontWeight.w600,
                color: Color(0xFF111111),
              ),
            ),
            const SizedBox(height: 10),
            Row(
              children: [
                Text(
                  itemCount,
                  style: TextStyle(
                    fontSize: 13,
                    height: 1.2,
                    color: Color(0xFF6E6E73),
                  ),
                ),
                const SizedBox(width: 10),
                Container(
                  padding: const EdgeInsets.symmetric(
                    horizontal: 12,
                    vertical: 6,
                  ),
                  decoration: BoxDecoration(
                    color: const Color(0xFFF1F2F6),
                    borderRadius: BorderRadius.circular(999),
                  ),
                  child: Text(
                    availableSpace,
                    style: TextStyle(
                      fontSize: 12,
                      height: 1.1,
                      fontWeight: FontWeight.w500,
                      color: Color(0xFF6E6E73),
                    ),
                  ),
                ),
              ],
            ),
          ],
        ),
      ),
    );
  }
}

class _FileListCard extends StatelessWidget {
  const _FileListCard({required this.directories, required this.entries});

  final List<FileDirectoryData> directories;
  final List<FileEntryData> entries;

  @override
  Widget build(BuildContext context) {
    return DecoratedBox(
      decoration: BoxDecoration(
        color: Colors.white,
        borderRadius: BorderRadius.circular(16),
      ),
      child: Column(
        children: [
          for (var index = 0; index < directories.length; index++) ...[
            _DirectoryRow(directory: directories[index]),
            const Divider(
              height: 1,
              color: FilesFeatureScreen._divider,
              indent: 16,
              endIndent: 16,
            ),
          ],
          for (var index = 0; index < entries.length; index++) ...[
            _FileRow(entry: entries[index]),
            if (index < entries.length - 1)
              const Divider(
                height: 1,
                color: FilesFeatureScreen._divider,
                indent: 16,
                endIndent: 16,
              ),
          ],
        ],
      ),
    );
  }
}

class _DirectoryRow extends StatelessWidget {
  const _DirectoryRow({required this.directory});

  final FileDirectoryData directory;

  @override
  Widget build(BuildContext context) {
    return Padding(
      padding: const EdgeInsets.fromLTRB(16, 14, 16, 14),
      child: Row(
        children: [
          const Icon(
            Icons.folder_rounded,
            color: FilesFeatureScreen._accent,
            size: 20,
          ),
          const SizedBox(width: 12),
          Expanded(
            child: Text(
              directory.name,
              style: const TextStyle(
                fontSize: 16,
                height: 1.2,
                fontWeight: FontWeight.w600,
                color: FilesFeatureScreen._textPrimary,
              ),
            ),
          ),
          Text(
            directory.itemCount,
            style: const TextStyle(
              fontSize: 13,
              height: 1.2,
              color: FilesFeatureScreen._textSecondary,
            ),
          ),
          const SizedBox(width: 6),
          const Icon(
            Icons.chevron_right_rounded,
            size: 18,
            color: FilesFeatureScreen._textTertiary,
          ),
        ],
      ),
    );
  }
}

class _FileRow extends StatelessWidget {
  const _FileRow({required this.entry});

  final FileEntryData entry;

  @override
  Widget build(BuildContext context) {
    return Padding(
      padding: const EdgeInsets.fromLTRB(16, 14, 16, 14),
      child: Row(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          const Padding(
            padding: EdgeInsets.only(top: 2),
            child: Icon(
              Icons.description_outlined,
              color: FilesFeatureScreen._textTertiary,
              size: 18,
            ),
          ),
          const SizedBox(width: 12),
          Expanded(
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Text(
                  entry.name,
                  style: const TextStyle(
                    fontSize: 15,
                    height: 1.25,
                    fontWeight: FontWeight.w500,
                    color: FilesFeatureScreen._textPrimary,
                  ),
                ),
                const SizedBox(height: 4),
                Text(
                  entry.meta,
                  style: const TextStyle(
                    fontSize: 13,
                    height: 1.2,
                    color: FilesFeatureScreen._textSecondary,
                  ),
                ),
              ],
            ),
          ),
          const SizedBox(width: 6),
          const Icon(
            Icons.chevron_right_rounded,
            size: 18,
            color: FilesFeatureScreen._textTertiary,
          ),
        ],
      ),
    );
  }
}
