import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:opencray/features/settings/settings_facade.dart';
import 'package:opencray/features/settings/settings_feature.dart';
import 'package:opencray/features/settings/settings_models.dart';

import 'settings_feature_test_support.dart';

void main() {
  testWidgets('mcp page shows add-server entry card', (tester) async {
    final facade = buildSettingsFacade();

    await tester.pumpWidget(
      MaterialApp(
        home: SettingsFeatureScreen(
          facade: facade,
          initialPage: SettingsPage.mcp,
          standalone: true,
        ),
      ),
    );
    await tester.pumpAndSettle();

    expect(find.text('MCP'), findsOneWidget);
    expect(find.byKey(const ValueKey<String>('settings-mcp-editor')), findsOneWidget);
  });

  testWidgets(
    'add-server sheet calls the facade and refreshes the page snapshot',
    (tester) async {
      var addCalls = 0;
      String? lastServerId;
      String? lastUrl;
      String? lastToken;
      var currentSnapshot = buildSettingsFacade().mcpSettings;
      final facade = buildSettingsFacade()
        ..onAddMcpServer = ({
          required serverId,
          required displayName,
          required url,
          authHeaderName,
          authToken,
        }) async {
          addCalls += 1;
          lastServerId = serverId;
          lastUrl = url;
          lastToken = authToken;
          return currentSnapshot;
        };

      await tester.pumpWidget(
        MaterialApp(
          home: SettingsFeatureScreen(
            facade: facade,
            initialPage: SettingsPage.mcp,
            standalone: true,
          ),
        ),
      );
      await tester.pumpAndSettle();

      // Copy is derived from the platform locale; tests run under en-US so the
      // English labels appear.
      await tester.ensureVisible(find.text('Add server'));
      await tester.tap(find.text('Add server'));
      await tester.pumpAndSettle();

      expect(find.text('Add MCP server'), findsOneWidget);
      expect(find.text('Server id'), findsOneWidget);

      // Hints live in the field decoration, so they locate the TextFormField.
      await tester.enterText(
        find.widgetWithText(TextFormField, 'e.g. search-demo'),
        'search-demo',
      );
      await tester.enterText(
        find.widgetWithText(TextFormField, 'e.g. Search demo'),
        'Search demo',
      );
      await tester.enterText(
        find.widgetWithText(TextFormField, 'https://example.com/mcp'),
        'https://demo.example.com/mcp',
      );
      await tester.enterText(
        find.widgetWithText(TextFormField, 'Leave blank for no auth. Tokens are stored in the device keystore only.'),
        'secret-token',
      );
      await tester.pump();

      final addButton = find.widgetWithText(FilledButton, 'Add');
      await tester.ensureVisible(addButton);
      await tester.tap(addButton);
      await tester.pumpAndSettle();

      expect(addCalls, 1);
      expect(lastServerId, 'search-demo');
      expect(lastUrl, 'https://demo.example.com/mcp');
      expect(lastToken, 'secret-token');
      // The sheet closes after a successful add.
      expect(find.text('Add MCP server'), findsNothing);
    },
  );

  testWidgets(
    'add-server sheet keeps the apply button disabled for invalid input',
    (tester) async {
      var addCalls = 0;
      final facade = buildSettingsFacade()
        ..onAddMcpServer = ({
          required serverId,
          required displayName,
          required url,
          authHeaderName,
          authToken,
        }) async {
          addCalls += 1;
          return buildSettingsFacade().mcpSettings;
        };

      await tester.pumpWidget(
        MaterialApp(
          home: SettingsFeatureScreen(
            facade: facade,
            initialPage: SettingsPage.mcp,
            standalone: true,
          ),
        ),
      );
      await tester.pumpAndSettle();

      await tester.ensureVisible(find.text('Add server'));
      await tester.tap(find.text('Add server'));
      await tester.pumpAndSettle();

      final applyButton = tester.widget<FilledButton>(
        find.ancestor(
          of: find.text('Add'),
          matching: find.byType(FilledButton),
        ),
      );
      expect(applyButton.onPressed, isNull);
    },
  );
}
