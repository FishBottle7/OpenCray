import 'package:flutter/gestures.dart';
import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_svg/flutter_svg.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:opencray/core/copy/opencray_ui_copy.dart';
import 'package:opencray/core/widgets/opencray_markdown.dart';

void main() {
  test('resolve internal markdown routes for settings pages', () {
    expect(
      openCrayResolveMarkdownInternalRoute('/settings/llm'),
      '/settings/llm',
    );
    expect(
      openCrayResolveMarkdownInternalRoute('/settings/network-search'),
      '/settings/network-search',
    );
    expect(openCrayResolveMarkdownInternalRoute('docs/README.md'), isNull);
    expect(
      openCrayResolveMarkdownInternalRoute('https://opencray.dev/docs'),
      isNull,
    );
  });

  test('resolve external markdown urls for bare domains and http links', () {
    expect(
      openCrayResolveMarkdownExternalUri(
        'https://opencray.dev/docs',
      )?.toString(),
      'https://opencray.dev/docs',
    );
    expect(
      openCrayResolveMarkdownExternalUri('opencray.dev/docs')?.toString(),
      'https://opencray.dev/docs',
    );
    expect(
      openCrayResolveMarkdownExternalUri('www.opencray.dev/docs')?.toString(),
      'https://www.opencray.dev/docs',
    );
    expect(openCrayResolveMarkdownExternalUri('docs/report.md'), isNull);
    expect(openCrayResolveMarkdownExternalUri('README.md'), isNull);
  });

  test(
    'resolve markdown workspace image paths from document-relative paths',
    () {
      expect(
        openCrayResolveMarkdownWorkspaceRelativePath(
          Uri.parse('./images/diagram.png'),
          documentRelativePath: 'docs/guide.md',
        ),
        'docs/images/diagram.png',
      );
      expect(
        openCrayResolveMarkdownWorkspaceRelativePath(
          Uri.parse('../shared/chart.png'),
          documentRelativePath: 'docs/guides/setup.md',
        ),
        'docs/shared/chart.png',
      );
      expect(
        openCrayResolveMarkdownWorkspaceRelativePath(
          Uri.parse('/assets/hero.png'),
          documentRelativePath: 'docs/guides/setup.md',
        ),
        'assets/hero.png',
      );
      expect(
        openCrayResolveMarkdownWorkspaceRelativePath(
          Uri.parse('../../../escape.png'),
          documentRelativePath: 'docs/guides/setup.md',
        ),
        isNull,
      );
    },
  );

  test('extract user-facing markdown link errors', () {
    expect(
      openCrayMarkdownUserFacingErrorMessage(
        StateError('No application can open this link.'),
      ),
      'No application can open this link.',
    );
    expect(
      openCrayMarkdownUserFacingErrorMessage(
        PlatformException(
          code: 'open_external_uri_failed',
          message: 'Failed to open the external link.',
        ),
      ),
      'Failed to open the external link.',
    );
  });

  test('localize markdown link errors', () {
    final OpenCrayUiCopy zhCopy = OpenCrayUiCopy.fromLocaleTag('zh-CN');
    final OpenCrayUiCopy enCopy = OpenCrayUiCopy.fromLocaleTag('en');

    expect(
      openCrayMarkdownLocalizedErrorMessage(
        StateError('No application can open this link.'),
        zhCopy,
      ),
      '没有可用的应用可以打开这个链接。',
    );
    expect(
      openCrayMarkdownLocalizedErrorMessage(
        StateError('Unsupported markdown link target.'),
        enCopy,
      ),
      'This link target is not supported.',
    );
  });

  test('build markdown clipboard payloads only when hyperlinks exist', () {
    final payload = openCrayBuildMarkdownClipboardPayload(
      'Open [docs](https://opencray.dev/docs)',
    );

    expect(payload, isNotNull);
    expect(payload?.plainText, 'Open https://opencray.dev/docs');
    expect(payload?.htmlText, contains('href="https://opencray.dev/docs"'));
    expect(payload?.htmlText, contains('>docs<'));
    expect(
      openCrayBuildMarkdownClipboardPayload('No hyperlinks here.'),
      isNull,
    );
  });

  test('build markdown selection clipboard payloads for full links', () {
    final payload = openCrayBuildMarkdownSelectionClipboardPayload(
      'Open [docs](https://opencray.dev/docs) now',
      selectedText: 'docs',
    );

    expect(payload, isNotNull);
    expect(payload?.plainText, 'https://opencray.dev/docs');
    expect(payload?.htmlText, contains('href="https://opencray.dev/docs"'));
    expect(payload?.htmlText, contains('>docs<'));
  });

  test(
    'build markdown selection clipboard payloads preserve full links inside larger selections',
    () {
      final payload = openCrayBuildMarkdownSelectionClipboardPayload(
        'Open [docs](https://opencray.dev/docs) now',
        selectedText: 'Open docs now',
      );

      expect(payload, isNotNull);
      expect(payload?.plainText, 'Open https://opencray.dev/docs now');
      expect(payload?.htmlText, contains('Open '));
      expect(payload?.htmlText, contains('href="https://opencray.dev/docs"'));
      expect(payload?.htmlText, contains(' now'));
    },
  );

  test(
    'build markdown selection clipboard payloads fall back on partial links',
    () {
      expect(
        openCrayBuildMarkdownSelectionClipboardPayload(
          'Open [docs](https://opencray.dev/docs) now',
          selectedText: 'doc',
        ),
        isNull,
      );
    },
  );

  testWidgets('selectable markdown uses a visible primary selection theme', (
    tester,
  ) async {
    late TextSelectionThemeData selectionTheme;
    late Color primary;

    await tester.pumpWidget(
      MaterialApp(
        home: Builder(
          builder: (context) {
            primary = Theme.of(context).colorScheme.primary;
            selectionTheme = openCrayMarkdownSelectionTheme(context);
            return const Scaffold(
              body: OpenCrayMarkdownBody(
                data: '```dart\nfinal answer = 42;\n```',
                selectable: true,
              ),
            );
          },
        ),
      ),
    );
    await tester.pumpAndSettle();

    expect(selectionTheme.selectionColor, primary.withValues(alpha: 0.32));
    expect(selectionTheme.selectionHandleColor, primary);
    expect(selectionTheme.cursorColor, primary);
    expect(find.byType(SelectableText), findsWidgets);
  });

  testWidgets('markdown links can push internal settings routes', (
    tester,
  ) async {
    await tester.pumpWidget(
      MaterialApp(
        routes: <String, WidgetBuilder>{
          '/settings/llm': (_) =>
              const Scaffold(body: Text('LLM Settings Screen')),
        },
        home: Builder(
          builder: (context) => Scaffold(
            body: OpenCrayMarkdownBody(
              data: 'Open [Settings -> LLM](/settings/llm)',
              onTapLink: (_, href, __) {
                final String? routeName = openCrayResolveMarkdownInternalRoute(
                  href,
                );
                if (routeName != null) {
                  Navigator.of(context).pushNamed(routeName);
                }
              },
            ),
          ),
        ),
      ),
    );
    await tester.pumpAndSettle();

    _activateRichTextLink(
      tester,
      _findRichTextWithPlainText('Open Settings -> LLM'),
    );
    await tester.pumpAndSettle();

    expect(find.text('LLM Settings Screen'), findsOneWidget);
  });

  testWidgets('markdown data images can open the shared preview dialog', (
    tester,
  ) async {
    const String markdown =
        '![pixel](data:image/png;base64,iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAusB9Wn7n8sAAAAASUVORK5CYII=)';

    await tester.pumpWidget(
      const MaterialApp(
        home: Scaffold(body: OpenCrayMarkdownBody(data: markdown)),
      ),
    );
    await tester.pumpAndSettle();

    await tester.tap(
      find.byKey(const ValueKey<String>('opencray-markdown-image-tappable')),
    );
    await tester.pumpAndSettle();

    expect(
      find.byKey(
        const ValueKey<String>('opencray-markdown-image-preview-dialog'),
      ),
      findsOneWidget,
    );
  });

  testWidgets('markdown svg data images render through flutter_svg', (
    tester,
  ) async {
    const String markdown =
        '![vector](data:image/svg+xml;base64,PHN2ZyB4bWxucz0iaHR0cDovL3d3dy53My5vcmcvMjAwMC9zdmciIHZpZXdCb3g9IjAgMCAxNiAxNiI+PHJlY3Qgd2lkdGg9IjE2IiBoZWlnaHQ9IjE2IiBmaWxsPSIjMDA3QUZGIi8+PC9zdmc+)';

    await tester.pumpWidget(
      const MaterialApp(
        home: Scaffold(body: OpenCrayMarkdownBody(data: markdown)),
      ),
    );
    await tester.pumpAndSettle();

    expect(find.byType(SvgPicture), findsOneWidget);

    await tester.tap(
      find.byKey(const ValueKey<String>('opencray-markdown-image-tappable')),
    );
    await tester.pumpAndSettle();

    expect(find.byType(SvgPicture), findsWidgets);
    expect(
      find.byKey(
        const ValueKey<String>('opencray-markdown-image-preview-dialog'),
      ),
      findsOneWidget,
    );
  });
}

List<TextSpan> _collectLeafTextSpans(InlineSpan span) {
  if (span is! TextSpan) {
    return const <TextSpan>[];
  }
  final List<InlineSpan>? children = span.children;
  if (children == null || children.isEmpty) {
    return <TextSpan>[span];
  }
  return children
      .expand<TextSpan>(_collectLeafTextSpans)
      .toList(growable: false);
}

Finder _findRichTextWithPlainText(String text) =>
    find.byWidgetPredicate((widget) {
      if (widget is! RichText) {
        return false;
      }
      return widget.text.toPlainText() == text;
    });

void _activateRichTextLink(WidgetTester tester, Finder richTextFinder) {
  final RichText richText = tester.widget<RichText>(richTextFinder);
  final TapGestureRecognizer recognizer = _collectLeafTextSpans(
    richText.text,
  ).map((span) => span.recognizer).whereType<TapGestureRecognizer>().first;
  recognizer.onTap?.call();
}
