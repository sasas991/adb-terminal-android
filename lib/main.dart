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

  String? _adbPath;
  String _device = '';
  bool _connected = false;
  bool _busy = false;
  final List<TerminalLine> _lines = [];
  final List<String> _history = [];
  int _historyIndex = -1;

  @override
  void initState() {
    super.initState();
    _initAdb();
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

  Future<void> _initAdb() async {
    try {
      final path = await _channel.invokeMethod<String>('getAdbPath');
      setState(() => _adbPath = path);
      _addLine('ADB binary: $path', LineType.info);
      _addLine('Введите IP устройства и нажмите "Подключить"', LineType.info);
    } catch (e) {
      _addLine('Ошибка инициализации ADB: $e', LineType.error);
    }
  }

  void _addLine(String text, LineType type) {
    setState(() {
      // Split long output into lines
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
    if (_busy || _adbPath == null) return;
    final host = _hostCtrl.text.trim();
    final port = _portCtrl.text.trim();
    if (host.isEmpty) return;
    final target = '$host:$port';

    setState(() => _busy = true);
    _addLine('adb connect $target', LineType.command);

    try {
      final out = await _channel.invokeMethod<String>('execute', {
        'args': ['connect', target],
      });
      final output = out ?? '';
      _addLine(output, LineType.output);
      setState(() {
        _connected = output.toLowerCase().contains('connected');
        _device = _connected ? target : '';
      });
    } catch (e) {
      _addLine('$e', LineType.error);
    } finally {
      setState(() => _busy = false);
    }
  }

  Future<void> _disconnect() async {
    if (_busy) return;
    setState(() => _busy = true);
    _addLine('adb disconnect', LineType.command);

    try {
      final out = await _channel.invokeMethod<String>('execute', {
        'args': ['disconnect'],
      });
      _addLine(out ?? '', LineType.output);
      setState(() {
        _connected = false;
        _device = '';
      });
    } catch (e) {
      _addLine('$e', LineType.error);
    } finally {
      setState(() => _busy = false);
    }
  }

  Future<void> _run(String command) async {
    final cmd = command.trim();
    if (cmd.isEmpty || _busy || _adbPath == null) return;

    _cmdCtrl.clear();
    _history.insert(0, cmd);
    if (_history.length > 50) _history.removeLast();
    _historyIndex = -1;

    setState(() => _busy = true);
    _addLine('adb $cmd', LineType.command);

    try {
      final parts = _split(cmd);
      final noDevice = {'connect', 'disconnect', 'devices', 'kill-server', 'start-server'};
      final args = (_device.isNotEmpty && !noDevice.contains(parts.first))
          ? ['-s', _device, ...parts]
          : parts;

      final out = await _channel.invokeMethod<String>('execute', {'args': args});
      _addLine(out ?? '(нет вывода)', LineType.output);
    } catch (e) {
      _addLine('$e', LineType.error);
    } finally {
      setState(() => _busy = false);
      _cmdFocus.requestFocus();
    }
  }

  List<String> _split(String cmd) {
    final parts = <String>[];
    final buf = StringBuffer();
    String? quote;
    for (final ch in cmd.split('')) {
      if (quote != null) {
        if (ch == quote) {
          quote = null;
        } else {
          buf.write(ch);
        }
      } else if (ch == '"' || ch == "'") {
        quote = ch;
      } else if (ch == ' ') {
        if (buf.isNotEmpty) {
          parts.add(buf.toString());
          buf.clear();
        }
      } else {
        buf.write(ch);
      }
    }
    if (buf.isNotEmpty) parts.add(buf.toString());
    return parts;
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

  Color _lineColor(LineType t) {
    switch (t) {
      case LineType.command:
        return const Color(0xFF4EC9B0);
      case LineType.error:
        return const Color(0xFFF44747);
      case LineType.info:
        return const Color(0xFF808080);
      case LineType.output:
        return const Color(0xFFD4D4D4);
    }
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
                  decoration: const InputDecoration(labelText: 'IP адрес'),
                  keyboardType: TextInputType.number,
                ),
              ),
              const SizedBox(width: 8),
              Expanded(
                flex: 1,
                child: TextField(
                  controller: _portCtrl,
                  style: const TextStyle(fontSize: 14),
                  decoration: const InputDecoration(labelText: 'Порт'),
                  keyboardType: TextInputType.number,
                ),
              ),
              const SizedBox(width: 8),
              _connected
                  ? ElevatedButton(
                      onPressed: _busy ? null : _disconnect,
                      style: ElevatedButton.styleFrom(
                        backgroundColor: const Color(0xFF8B0000),
                      ),
                      child: const Text('Отключить'),
                    )
                  : ElevatedButton(
                      onPressed: (_busy || _adbPath == null) ? null : _connect,
                      style: ElevatedButton.styleFrom(
                        backgroundColor: const Color(0xFF007ACC),
                      ),
                      child: const Text('Подключить'),
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
                _connected ? 'Подключено: $_device' : 'Не подключено',
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
      ('devices', 'devices'),
      ('shell pwd', 'shell pwd'),
      ('shell ls /sdcard', 'shell ls /sdcard'),
      ('shell getprop ro.product.model', 'model'),
      ('logcat -d -t 50', 'logcat'),
      ('shell df -h', 'df'),
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
              onPressed: (_busy || _adbPath == null) ? null : () => _run(e.$1),
              style: OutlinedButton.styleFrom(
                padding: const EdgeInsets.symmetric(horizontal: 10, vertical: 0),
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
      child: Container(
        color: const Color(0xFF1E1E1E),
        child: ListView.builder(
          controller: _scrollCtrl,
          padding: const EdgeInsets.symmetric(horizontal: 10, vertical: 8),
          itemCount: _lines.length,
          itemBuilder: (_, i) {
            final line = _lines[i];
            return Text(
              line.type == LineType.command ? '▶ ${line.text}' : line.text,
              style: TextStyle(
                fontFamily: 'monospace',
                fontSize: 12.5,
                height: 1.4,
                color: _lineColor(line.type),
              ),
            );
          },
        ),
      ),
    );
  }

  Widget _buildInputBar() {
    return Container(
      color: const Color(0xFF252526),
      padding: const EdgeInsets.all(8),
      child: Row(
        children: [
          const Text(
            'adb ',
            style: TextStyle(
              fontFamily: 'monospace',
              fontSize: 13,
              color: Color(0xFF4EC9B0),
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
                  hintText: 'shell ls  /  connect ip:port  /  devices',
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
            onPressed: (_busy || _adbPath == null) ? null : () => _run(_cmdCtrl.text),
            color: const Color(0xFF4EC9B0),
          ),
        ],
      ),
    );
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(
        backgroundColor: const Color(0xFF323233),
        title: const Text(
          'ADB Terminal',
          style: TextStyle(fontFamily: 'monospace', fontSize: 16),
        ),
        actions: [
          IconButton(
            icon: const Icon(Icons.delete_sweep, size: 20),
            tooltip: 'Очистить',
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
}
