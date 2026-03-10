import 'package:flutter/material.dart';
import 'package:flutter/services.dart';

void main() => runApp(const AdbTerminalApp());

class AdbTerminalApp extends StatelessWidget {
  const AdbTerminalApp({super.key});

  @override
  Widget build(BuildContext context) {
    return MaterialApp(
      title: 'ADB Terminal',
      debugShowCheckedModeBanner: false,
      theme: ThemeData.dark().copyWith(
        scaffoldBackgroundColor: const Color(0xFF1E1E1E),
        colorScheme: const ColorScheme.dark(
          primary: Color(0xFF4EC9B0),
          surface: Color(0xFF252526),
        ),
        inputDecorationTheme: InputDecorationTheme(
          filled: true,
          fillColor: const Color(0xFF3C3C3C),
          contentPadding: const EdgeInsets.symmetric(horizontal: 12, vertical: 8),
          border: OutlineInputBorder(
            borderRadius: BorderRadius.circular(4),
            borderSide: BorderSide.none,
          ),
        ),
      ),
      home: const TerminalScreen(),
    );
  }
}

enum LineType { command, output, error, info }

class TerminalLine {
  final String text;
  final LineType type;
  TerminalLine(this.text, this.type);
}

class TerminalScreen extends StatefulWidget {
  const TerminalScreen({super.key});

  @override
  State<TerminalScreen> createState() => _TerminalScreenState();
}

class _TerminalScreenState extends State<TerminalScreen> {
  static const _channel = MethodChannel('adb_terminal');

  final _hostCtrl = TextEditingController(text: '192.168.1.1');
  final _portCtrl = TextEditingController(text: '5555');
  final _cmdCtrl = TextEditingController();
  final _scrollCtrl = ScrollController();
  final _cmdFocus = FocusNode();

  String _device = '';
  bool _connected = false;
  bool _busy = false;
  final List<TerminalLine> _lines = [];
  final List<String> _history = [];
  int _historyIndex = -1;

  @override
  void initState() {
    super.initState();
    _addLine('Enter the target device IP and tap Connect.', LineType.info);
    _addLine('Make sure ADB over Wi-Fi is enabled on the target device (adb tcpip 5555).', LineType.info);
  }

  @override
  void dispose() {
    _hostCtrl.dispose();
    _portCtrl.dispose();
    _cmdCtrl.dispose();
    _scrollCtrl.dispose();
    _cmdFocus.dispose();
    super.dispose();
  }

  void _addLine(String text, LineType type) {
    setState(() {
      for (final line in text.split('\n')) {
        _lines.add(TerminalLine(line, type));
      }
    });
    WidgetsBinding.instance.addPostFrameCallback((_) {
      if (_scrollCtrl.hasClients) {
        _scrollCtrl.animateTo(
          _scrollCtrl.position.maxScrollExtent,
          duration: const Duration(milliseconds: 150),
          curve: Curves.easeOut,
        );
      }
    });
  }

  Future<void> _connect() async {
    if (_busy) return;
    final host = _hostCtrl.text.trim();
    final port = int.tryParse(_portCtrl.text.trim()) ?? 5555;
    if (host.isEmpty) return;

    setState(() => _busy = true);
    _addLine('Connecting to $host:$port...', LineType.info);

    try {
      final out = await _channel.invokeMethod<String>('connect', {
        'host': host,
        'port': port,
      });
      _addLine(out ?? '', LineType.output);
      setState(() {
        _connected = true;
        _device = '$host:$port';
      });
      _cmdFocus.requestFocus();
    } on PlatformException catch (e) {
      _addLine('Error: ${e.message}', LineType.error);
      _addLine('Make sure the target device is running: adb tcpip 5555', LineType.info);
    } finally {
      setState(() => _busy = false);
    }
  }

  Future<void> _disconnect() async {
    if (_busy) return;
    setState(() => _busy = true);

    try {
      final out = await _channel.invokeMethod<String>('disconnect');
      _addLine(out ?? 'Disconnected', LineType.info);
      setState(() {
        _connected = false;
        _device = '';
      });
    } on PlatformException catch (e) {
      _addLine('Error: ${e.message}', LineType.error);
    } finally {
      setState(() => _busy = false);
    }
  }

  Future<void> _run(String input) async {
    final cmd = input.trim();
    if (cmd.isEmpty || _busy) return;
    if (!_connected) {
      _addLine('Not connected. Connect to a device first.', LineType.error);
      return;
    }

    _cmdCtrl.clear();
    _history.insert(0, cmd);
    if (_history.length > 100) _history.removeLast();
    _historyIndex = -1;

    setState(() => _busy = true);
    _addLine('\$ $cmd', LineType.command);

    // Strip "shell " prefix if user typed it — dadb.shell() handles the shell
    final shellCmd = cmd.startsWith('shell ') ? cmd.substring(6) : cmd;

    try {
      final out = await _channel.invokeMethod<String>('execute', {
        'command': shellCmd,
      });
      _addLine(out ?? '(no output)', LineType.output);
    } on PlatformException catch (e) {
      _addLine('Error: ${e.message}', LineType.error);
    } finally {
      setState(() => _busy = false);
      _cmdFocus.requestFocus();
    }
  }

  void _historyUp() {
    if (_history.isEmpty) return;
    final next = (_historyIndex + 1).clamp(0, _history.length - 1);
    setState(() => _historyIndex = next);
    _cmdCtrl.text = _history[_historyIndex];
    _cmdCtrl.selection = TextSelection.collapsed(offset: _cmdCtrl.text.length);
  }

  void _historyDown() {
    if (_historyIndex <= 0) {
      setState(() => _historyIndex = -1);
      _cmdCtrl.clear();
      return;
    }
    setState(() => _historyIndex--);
    _cmdCtrl.text = _history[_historyIndex];
    _cmdCtrl.selection = TextSelection.collapsed(offset: _cmdCtrl.text.length);
  }

  Color _lineColor(LineType t) => switch (t) {
        LineType.command => const Color(0xFF4EC9B0),
        LineType.error => const Color(0xFFF44747),
        LineType.info => const Color(0xFF808080),
        LineType.output => const Color(0xFFD4D4D4),
      };

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(
        backgroundColor: const Color(0xFF323233),
        title: const Text('ADB Terminal', style: TextStyle(fontFamily: 'monospace', fontSize: 16)),
        actions: [
          IconButton(
            icon: const Icon(Icons.delete_sweep, size: 20),
            tooltip: 'Clear',
            onPressed: () => setState(() => _lines.clear()),
          ),
        ],
      ),
      body: Column(
        children: [
          _buildConnectionBar(),
          _buildQuickButtons(),
          _buildTerminal(),
          _buildInputBar(),
        ],
      ),
    );
  }

  Widget _buildConnectionBar() {
    return Container(
      color: const Color(0xFF252526),
      padding: const EdgeInsets.all(8),
      child: Column(
        children: [
          Row(
            children: [
              Expanded(
                flex: 3,
                child: TextField(
                  controller: _hostCtrl,
                  style: const TextStyle(fontSize: 14),
                  decoration: const InputDecoration(labelText: 'IP address'),
                  keyboardType: TextInputType.number,
                  enabled: !_connected && !_busy,
                ),
              ),
              const SizedBox(width: 8),
              Expanded(
                child: TextField(
                  controller: _portCtrl,
                  style: const TextStyle(fontSize: 14),
                  decoration: const InputDecoration(labelText: 'Port'),
                  keyboardType: TextInputType.number,
                  enabled: !_connected && !_busy,
                ),
              ),
              const SizedBox(width: 8),
              _connected
                  ? ElevatedButton(
                      onPressed: _busy ? null : _disconnect,
                      style: ElevatedButton.styleFrom(backgroundColor: const Color(0xFF8B0000)),
                      child: const Text('Disconnect'),
                    )
                  : ElevatedButton(
                      onPressed: _busy ? null : _connect,
                      style: ElevatedButton.styleFrom(backgroundColor: const Color(0xFF007ACC)),
                      child: const Text('Connect'),
                    ),
            ],
          ),
          const SizedBox(height: 6),
          Row(
            children: [
              Container(
                width: 8,
                height: 8,
                decoration: BoxDecoration(
                  color: _connected ? Colors.green : Colors.red,
                  shape: BoxShape.circle,
                ),
              ),
              const SizedBox(width: 6),
              Text(
                _connected ? 'Connected: $_device' : 'Not connected',
                style: TextStyle(
                  fontSize: 12,
                  color: _connected ? Colors.green[300] : Colors.red[300],
                ),
              ),
              if (_busy) ...[
                const SizedBox(width: 12),
                const SizedBox(
                  width: 12,
                  height: 12,
                  child: CircularProgressIndicator(strokeWidth: 2),
                ),
              ],
            ],
          ),
        ],
      ),
    );
  }

  Widget _buildQuickButtons() {
    final cmds = [
      ('ls /sdcard', 'ls /sdcard'),
      ('pwd', 'pwd'),
      ('id', 'id'),
      ('getprop ro.product.model', 'model'),
      ('df -h', 'df -h'),
      ('logcat -d -t 50', 'logcat'),
      ('ps -A | head -20', 'ps'),
      ('ip addr', 'ip addr'),
    ];
    return Container(
      color: const Color(0xFF252526),
      height: 36,
      child: ListView(
        scrollDirection: Axis.horizontal,
        padding: const EdgeInsets.symmetric(horizontal: 8, vertical: 4),
        children: cmds.map((e) {
          return Padding(
            padding: const EdgeInsets.only(right: 6),
            child: OutlinedButton(
              onPressed: (_busy || !_connected) ? null : () => _run(e.$1),
              style: OutlinedButton.styleFrom(
                padding: const EdgeInsets.symmetric(horizontal: 10),
                minimumSize: Size.zero,
                tapTargetSize: MaterialTapTargetSize.shrinkWrap,
                side: const BorderSide(color: Color(0xFF555555)),
                foregroundColor: const Color(0xFF9CDCFE),
              ),
              child: Text(e.$2, style: const TextStyle(fontSize: 11)),
            ),
          );
        }).toList(),
      ),
    );
  }

  Widget _buildTerminal() {
    return Expanded(
      child: ListView.builder(
        controller: _scrollCtrl,
        padding: const EdgeInsets.symmetric(horizontal: 10, vertical: 8),
        itemCount: _lines.length,
        itemBuilder: (_, i) {
          final line = _lines[i];
          return Text(
            line.text,
            style: TextStyle(
              fontFamily: 'monospace',
              fontSize: 12.5,
              height: 1.4,
              color: _lineColor(line.type),
            ),
          );
        },
      ),
    );
  }

  Widget _buildInputBar() {
    return Container(
      color: const Color(0xFF252526),
      padding: const EdgeInsets.all(8),
      child: Row(
        children: [
          Text(
            _connected ? '\$ ' : '> ',
            style: TextStyle(
              fontFamily: 'monospace',
              fontSize: 14,
              color: _connected ? const Color(0xFF4EC9B0) : const Color(0xFF808080),
            ),
          ),
          Expanded(
            child: KeyboardListener(
              focusNode: FocusNode(),
              onKeyEvent: (event) {
                if (event is KeyDownEvent) {
                  if (event.logicalKey == LogicalKeyboardKey.arrowUp) _historyUp();
                  if (event.logicalKey == LogicalKeyboardKey.arrowDown) _historyDown();
                }
              },
              child: TextField(
                controller: _cmdCtrl,
                focusNode: _cmdFocus,
                style: const TextStyle(fontFamily: 'monospace', fontSize: 13),
                decoration: const InputDecoration(
                  hintText: 'ls /sdcard  /  getprop  /  logcat -d',
                  hintStyle: TextStyle(color: Color(0xFF555555)),
                ),
                onSubmitted: _run,
                textInputAction: TextInputAction.send,
                autocorrect: false,
                enableSuggestions: false,
              ),
            ),
          ),
          const SizedBox(width: 8),
          IconButton(
            icon: const Icon(Icons.send, size: 20),
            onPressed: (_busy || !_connected) ? null : () => _run(_cmdCtrl.text),
            color: const Color(0xFF4EC9B0),
          ),
        ],
      ),
    );
  }
}
