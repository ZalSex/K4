import 'dart:convert';
import 'package:flutter/services.dart';
import 'package:http/http.dart' as http;
import 'package:shared_preferences/shared_preferences.dart';

class ApiService {
  static String _baseUrl = '';
  static bool _initialized = false;

  static Future<void> init() async {
    if (_initialized) return;
    try {
      final raw = await rootBundle.loadString('assets/settings.json');
      final json = jsonDecode(raw) as Map<String, dynamic>;
      _baseUrl = (json['api_url'] as String? ?? '').replaceAll(RegExp(r'/$'), '');
    } catch (_) {
      _baseUrl = '';
    }
    // Simpan ke prefs supaya Kotlin service bisa baca
    final prefs = await SharedPreferences.getInstance();
    await prefs.setString('serverUrl', _baseUrl);
    _initialized = true;
  }

  static String get baseUrl => _baseUrl;

  static Future<Map<String, dynamic>> post(String path, Map<String, dynamic> body,
      {String? token}) async {
    await init();
    final uri = Uri.parse('$_baseUrl$path');
    final headers = <String, String>{
      'Content-Type': 'application/json',
      if (token != null) 'Authorization': 'Bearer $token',
    };
    final res = await http.post(uri, headers: headers, body: jsonEncode(body))
        .timeout(const Duration(seconds: 15));
    return jsonDecode(res.body) as Map<String, dynamic>;
  }

  static Future<Map<String, dynamic>> get(String path, {String? token}) async {
    await init();
    final uri = Uri.parse('$_baseUrl$path');
    final headers = <String, String>{
      if (token != null) 'Authorization': 'Bearer $token',
    };
    final res = await http.get(uri, headers: headers)
        .timeout(const Duration(seconds: 15));
    return jsonDecode(res.body) as Map<String, dynamic>;
  }
}
