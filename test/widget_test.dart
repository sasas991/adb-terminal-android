import 'package:flutter_test/flutter_test.dart';
import 'package:adb_terminal_android/main.dart';

void main() {
  testWidgets('App smoke test', (WidgetTester tester) async {
    await tester.pumpWidget(const AdbTerminalApp());
    expect(find.text('ADB Terminal'), findsOneWidget);
  });
}
