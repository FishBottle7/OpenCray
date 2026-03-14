import 'package:flutter/material.dart';

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
  const FilesFeatureScreen({super.key});

  static const _shellBackground = Color(0xFFF5F5F7);
  static const _textPrimary = Color(0xFF111111);
  static const _textSecondary = Color(0xFF6E6E73);
  static const _textTertiary = Color(0xFF8E8E93);
  static const _accent = Color(0xFF007AFF);
  static const _divider = Color(0xFFE5E5EA);

  static const List<FileDirectoryData> _directories = [
    FileDirectoryData(name: 'ui', itemCount: '12 items'),
    FileDirectoryData(name: 'app', itemCount: '8 items'),
    FileDirectoryData(name: 'docs', itemCount: '19 items'),
  ];

  static const List<FileEntryData> _entries = [
    FileEntryData(name: 'chat_feature_screen.dart', meta: '35 KB   12:58 AM'),
    FileEntryData(name: 'SkillsScreen.kt', meta: '21 KB   Yesterday'),
    FileEntryData(name: 'mobile-ui-layout-spec.md', meta: '12 KB   Mar 11'),
  ];

  @override
  Widget build(BuildContext context) {
    return const ColoredBox(
      color: _shellBackground,
      child: SafeArea(
        bottom: false,
        child: SingleChildScrollView(
          padding: EdgeInsets.fromLTRB(20, 8, 20, 24),
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.stretch,
            children: [
              Text(
                'Files',
                style: TextStyle(
                  fontSize: 30,
                  height: 1.05,
                  fontWeight: FontWeight.w600,
                  color: _textPrimary,
                ),
              ),
              SizedBox(height: 16),
              _SearchBar(),
              SizedBox(height: 12),
              _LocationCard(),
              SizedBox(height: 12),
              _FileListCard(directories: _directories, entries: _entries),
            ],
          ),
        ),
      ),
    );
  }
}

class _SearchBar extends StatelessWidget {
  const _SearchBar();

  @override
  Widget build(BuildContext context) {
    return DecoratedBox(
      decoration: BoxDecoration(
        color: Colors.white,
        borderRadius: BorderRadius.circular(12),
      ),
      child: const Padding(
        padding: EdgeInsets.symmetric(horizontal: 12, vertical: 12),
        child: Row(
          children: [
            Icon(Icons.search_rounded, size: 18, color: Color(0xFF8E8E93)),
            SizedBox(width: 10),
            Expanded(
              child: Text(
                'Search files and folders',
                style: TextStyle(
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
  const _LocationCard();

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
            const Text(
              'Location',
              style: TextStyle(
                fontSize: 12,
                height: 1.2,
                color: Color(0xFF8E8E93),
              ),
            ),
            const SizedBox(height: 6),
            const Text(
              'OpenCray / src / main',
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
                const Text(
                  '622 items',
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
                  child: const Text(
                    '4.1 GB available',
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
