package com.streamline.play.data.epg

import android.content.Context
import android.util.Base64
import app.cash.quickjs.QuickJs

/**
 * JavaScript gzip fallback backed by pako running in QuickJS. Native
 * GZIPInputStream handles well-formed guides; this path exists for payloads the
 * platform inflater refuses (multi-member streams, trailing garbage, proxied
 * bodies that keep a .gz extension without usable headers).
 */
object PakoGunzip {

    private const val MAX_COMPRESSED_BYTES = 8 * 1024 * 1024
    private const val ASSET = "pako.min.js"

    @Volatile
    private var engineScript: String? = null

    fun ungzip(context: Context, bytes: ByteArray): ByteArray? {
        if (bytes.size > MAX_COMPRESSED_BYTES) return null
        val script = engineScript ?: loadScript(context) ?: return null
        val input = Base64.encodeToString(bytes, Base64.NO_WRAP)
        return runCatching {
            QuickJs.create().use { js ->
                js.set("__pakoInput", String::class.java, input)
                val encoded = js.evaluate(script) as? String ?: return null
                Base64.decode(encoded, Base64.DEFAULT)
            }
        }.getOrNull()
    }

    private fun loadScript(context: Context): String? = synchronized(this) {
        engineScript ?: runCatching {
            val pako = context.applicationContext.assets.open(ASSET)
                .bufferedReader()
                .use { it.readText() }
            "$pako\n$BRIDGE"
        }.getOrNull()?.also { engineScript = it }
    }

    private val BRIDGE = """
        ;(function () {
          var C = 'ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/';
          var L = {};
          for (var i = 0; i < 64; i++) { L[C.charAt(i)] = i; }
          function dec(s) {
            s = s.replace(/[^A-Za-z0-9+/]/g, '');
            while (s.length % 4 !== 0) { s += '='; }
            var n = 0, o = 0, bits = 0, out = new Uint8Array((s.length / 4) * 3);
            for (var i = 0; i < s.length; i++) {
              var ch = s.charAt(i);
              if (ch === '=') { break; }
              n = ((n << 6) | L[ch]) & 0xFFFFFF;
              bits += 6;
              if (bits >= 8) { bits -= 8; out[o++] = (n >> bits) & 255; }
            }
            return out.subarray(0, o);
          }
          function enc(b) {
            var r = '', i = 0;
            for (; i + 2 < b.length; i += 3) {
              var n = (b[i] << 16) | (b[i + 1] << 8) | b[i + 2];
              r += C.charAt((n >> 18) & 63) + C.charAt((n >> 12) & 63) + C.charAt((n >> 6) & 63) + C.charAt(n & 63);
            }
            var rem = b.length - i;
            if (rem === 1) {
              var n = b[i] << 16;
              r += C.charAt((n >> 18) & 63) + C.charAt((n >> 12) & 63) + '==';
            } else if (rem === 2) {
              var n = (b[i] << 16) | (b[i + 1] << 8);
              r += C.charAt((n >> 18) & 63) + C.charAt((n >> 12) & 63) + C.charAt((n >> 6) & 63) + '=';
            }
            return r;
          }
          var inflated = pako.ungzip(dec(__pakoInput));
          if (inflated.length > 100663296) { throw new Error('EPG payload too large'); }
          return enc(inflated);
        })()
    """.trimIndent()
}
