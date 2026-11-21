package tv.own.owntv.plugin.stalkermacpool.network

import android.graphics.Bitmap
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONObject
import tv.own.owntv.plugin.stalkermacpool.model.ImportMode
import tv.own.owntv.plugin.stalkermacpool.model.ImportSummary
import tv.own.owntv.plugin.stalkermacpool.model.StalkerMacPoolDefaults
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder

/**
 * Self-contained lightweight LAN HTTP upload server for importing large Stalker JSON files
 * from a phone or PC browser without needing a USB drive.
 */
class LanJsonUploadServer {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile
    private var serverSocket: ServerSocket? = null

    @Volatile
    private var acceptJob: Job? = null

    fun start(
        onJsonUploaded: suspend (jsonText: String, mode: ImportMode) -> ImportSummary,
        onError: (String) -> Unit,
    ): String? {
        stop()
        val ip = detectLanIpv4()
        if (ip == null) {
            onError("No active Wi-Fi or Ethernet IPv4 address")
            return null
        }

        val socket = runCatching { ServerSocket(StalkerMacPoolDefaults.LAN_PREFERRED_PORT) }
            .recoverCatching { ServerSocket(0) }
            .getOrElse { e ->
                onError(e.message ?: "Bind failed")
                return null
            }

        serverSocket = socket
        val url = "http://$ip:${socket.localPort}/"

        acceptJob = scope.launch {
            while (isActive && !socket.isClosed) {
                val client = runCatching { socket.accept() }.getOrNull() ?: break
                launch {
                    handleClient(client, onJsonUploaded)
                }
            }
        }
        return url
    }

    fun stop() {
        acceptJob?.cancel()
        acceptJob = null
        runCatching { serverSocket?.close() }
        serverSocket = null
    }

    private suspend fun handleClient(
        socket: Socket,
        onJsonUploaded: suspend (jsonText: String, mode: ImportMode) -> ImportSummary,
    ) {
        socket.soTimeout = 15_000
        try {
            socket.use { s ->
                val input = BufferedInputStream(s.getInputStream(), 16 * 1024)
                val headerBlock = readHeaders(input) ?: return
                val lines = headerBlock.split("\r\n")
                val requestLine = lines.firstOrNull()?.trim().orEmpty()
                val parts = requestLine.split(' ')
                if (parts.size < 2) {
                    writeResponse(s, 400, "Bad Request", "text/plain; charset=utf-8", "Bad Request")
                    return
                }
                val method = parts[0].uppercase()
                val rawTarget = parts[1]
                val path = rawTarget.substringBefore('?')
                val query = rawTarget.substringAfter('?', "")

                val headers = HashMap<String, String>()
                for (line in lines.drop(1)) {
                    val idx = line.indexOf(':')
                    if (idx > 0) {
                        headers[line.substring(0, idx).trim().lowercase()] = line.substring(idx + 1).trim()
                    }
                }

                when {
                    method == "OPTIONS" -> {
                        writeResponse(s, 204, "No Content", "text/plain; charset=utf-8", "")
                    }
                    method == "GET" && (path == "/" || path == "/index.html") -> {
                        writeResponse(s, 200, "OK", "text/html; charset=utf-8", buildUploadHtml())
                    }
                    method == "POST" && path == "/upload" -> {
                        val contentLength = headers["content-length"]?.toIntOrNull()
                        if (contentLength == null || contentLength <= 0 || contentLength > MAX_UPLOAD_BYTES) {
                            writeResponse(
                                s,
                                413,
                                "Payload Too Large",
                                "application/json; charset=utf-8",
                                JSONObject().put("ok", false).put("error", "Invalid or oversized file (max 32MB)").toString(),
                            )
                            return
                        }
                        val bodyBytes = readExactBytes(input, contentLength)
                        val bodyText = bodyBytes.toString(Charsets.UTF_8)
                        val mode = parseImportMode(query)
                        val result = runCatching { onJsonUploaded(bodyText, mode) }
                        result.onSuccess { summary ->
                            val jsonResp = JSONObject()
                                .put("ok", true)
                                .put("servers", summary.serverCount)
                                .put("macs", summary.uniqueRecords)
                                .put("added", summary.addedCount)
                                .put("updated", summary.updatedCount)
                                .toString()
                            writeResponse(s, 200, "OK", "application/json; charset=utf-8", jsonResp)
                        }.onFailure { err ->
                            if (err is CancellationException) throw err
                            val jsonResp = JSONObject()
                                .put("ok", false)
                                .put("error", err.message ?: "Invalid JSON")
                                .toString()
                            writeResponse(s, 400, "Bad Request", "application/json; charset=utf-8", jsonResp)
                        }
                    }
                    else -> {
                        writeResponse(s, 404, "Not Found", "text/plain; charset=utf-8", "Not Found")
                    }
                }
            }
        } catch (_: CancellationException) {
            // Normal shutdown
        } catch (_: Exception) {
            // Ignore malformed client socket errors
        }
    }

    private fun readHeaders(input: InputStream): String? {
        val baos = ByteArrayOutputStream(1024)
        var state = 0
        while (baos.size() < MAX_HEADER_BYTES) {
            val b = input.read()
            if (b < 0) return null
            baos.write(b)
            state = when (state) {
                0 -> if (b == '\r'.code) 1 else 0
                1 -> if (b == '\n'.code) 2 else if (b == '\r'.code) 1 else 0
                2 -> if (b == '\r'.code) 3 else 0
                3 -> if (b == '\n'.code) break else if (b == '\r'.code) 1 else 0
                else -> 0
            }
        }
        return baos.toByteArray().toString(Charsets.ISO_8859_1).trimEnd('\r', '\n')
    }

    private fun readExactBytes(input: InputStream, size: Int): ByteArray {
        val out = ByteArray(size)
        var offset = 0
        while (offset < size) {
            val read = input.read(out, offset, size - offset)
            if (read < 0) break
            offset += read
        }
        return if (offset == size) out else out.copyOf(offset)
    }

    private fun parseImportMode(query: String): ImportMode {
        for (pair in query.split('&')) {
            val k = pair.substringBefore('=')
            val v = URLDecoder.decode(pair.substringAfter('=', ""), "UTF-8")
            if (k == "mode" && v.equals("REPLACE", ignoreCase = true)) {
                return ImportMode.REPLACE
            }
        }
        return ImportMode.MERGE
    }

    private fun writeResponse(
        socket: Socket,
        code: Int,
        status: String,
        contentType: String,
        body: String,
    ) {
        val bytes = body.toByteArray(Charsets.UTF_8)
        val headers = buildString {
            append("HTTP/1.1 ").append(code).append(' ').append(status).append("\r\n")
            append("Content-Type: ").append(contentType).append("\r\n")
            append("Content-Length: ").append(bytes.size).append("\r\n")
            append("Cache-Control: no-store\r\n")
            append("Connection: close\r\n\r\n")
        }.toByteArray(Charsets.ISO_8859_1)
        socket.getOutputStream().apply {
            write(headers)
            write(bytes)
            flush()
        }
    }

    private fun buildUploadHtml(): String = """
        <!doctype html>
        <html lang="zh">
        <head>
          <meta charset="utf-8" />
          <meta name="viewport" content="width=device-width,initial-scale=1" />
          <title>SkMate Stalker MAC Pool Upload</title>
          <style>
            body{font-family:system-ui,-apple-system,sans-serif;background:#0b0f14;color:#eef2f7;margin:0;padding:24px;display:flex;justify-content:center}
            .card{max-width:480px;width:100%;background:#141c24;border:1px solid #243240;border-radius:16px;padding:22px}
            h1{font-size:20px;margin:0 0 8px}
            p{font-size:14px;color:#9fb0c2;line-height:1.5;margin:0 0 18px}
            label{display:block;font-size:14px;margin-bottom:8px;color:#cbd5e1}
            select,input[type=file]{width:100%;padding:10px;border-radius:10px;border:1px solid #2b3a4a;background:#0e151d;color:#eef2f7;margin-bottom:16px;box-sizing:border-box}
            button{width:100%;padding:12px;border-radius:10px;border:0;background:#2dd4bf;color:#08201e;font-weight:700;font-size:15px;cursor:pointer}
            button:disabled{opacity:.5}
            #msg{margin-top:16px;font-size:14px;line-height:1.5}
            .ok{color:#34d399}.err{color:#f87171}
          </style>
        </head>
        <body>
          <div class="card">
            <h1>Stalker MAC 池导入 / JSON Upload</h1>
            <p>选择导出的 Stalker JSON 文件并发送到电视。</p>
            <label for="mode">导入模式 / Import mode</label>
            <select id="mode">
              <option value="MERGE">合并导入 / Merge</option>
              <option value="REPLACE">清空覆盖 / Replace All</option>
            </select>
            <label for="file">JSON 文件 / JSON file</label>
            <input id="file" type="file" accept=".json,application/json" />
            <button id="btn">上传到电视 / Upload</button>
            <div id="msg"></div>
          </div>
          <script>
            const btn=document.getElementById('btn'), msg=document.getElementById('msg');
            btn.onclick=async()=>{
              const f=document.getElementById('file').files[0];
              if(!f){msg.className='err';msg.textContent='请先选择一个 .json 文件 / Please choose a .json file';return;}
              btn.disabled=true;msg.className='';msg.textContent='正在上传解析... / Uploading...';
              try{
                const mode=document.getElementById('mode').value;
                const text=await f.text();
                const res=await fetch('/upload?mode='+encodeURIComponent(mode),{method:'POST',headers:{'Content-Type':'application/json'},body:text});
                const data=await res.json();
                if(data.ok){
                  msg.className='ok';
                  msg.textContent='上传成功！服务器: '+data.servers+'，MAC: '+data.macs+'（新增 '+data.added+'，更新 '+data.updated+'）';
                }else{
                  msg.className='err';msg.textContent='失败: '+(data.error||'Unknown error');
                }
              }catch(e){
                msg.className='err';msg.textContent='网络错误: '+e.message;
              }finally{btn.disabled=false;}
            };
          </script>
        </body>
        </html>
    """.trimIndent()

    companion object {
        private const val MAX_UPLOAD_BYTES = 32 * 1024 * 1024
        private const val MAX_HEADER_BYTES = 16 * 1024

        fun detectLanIpv4(): String? {
            val interfaces = runCatching { NetworkInterface.getNetworkInterfaces() }.getOrNull() ?: return null
            var fallback: String? = null
            while (interfaces.hasMoreElements()) {
                val iface = interfaces.nextElement()
                if (!iface.isUp || iface.isLoopback || iface.isVirtual) continue
                val addrs = iface.inetAddresses
                while (addrs.hasMoreElements()) {
                    val addr = addrs.nextElement()
                    if (addr is Inet4Address && !addr.isLoopbackAddress) {
                        if (addr.isSiteLocalAddress) return addr.hostAddress
                        if (fallback == null) fallback = addr.hostAddress
                    }
                }
            }
            return fallback
        }

        fun generateQrBitmap(content: String, sizePx: Int = 340): Bitmap? {
            return runCatching {
                val hints = mapOf(EncodeHintType.MARGIN to 1)
                val matrix = QRCodeWriter().encode(content, BarcodeFormat.QR_CODE, sizePx, sizePx, hints)
                val pixels = IntArray(sizePx * sizePx)
                val dark = 0xFF0B0F14.toInt()
                val light = 0xFFFFFFFF.toInt()
                for (y in 0 until sizePx) {
                    val rowOffset = y * sizePx
                    for (x in 0 until sizePx) {
                        pixels[rowOffset + x] = if (matrix[x, y]) dark else light
                    }
                }
                Bitmap.createBitmap(pixels, sizePx, sizePx, Bitmap.Config.ARGB_8888)
            }.getOrNull()
        }
    }
}
