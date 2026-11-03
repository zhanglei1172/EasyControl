package top.saymzx.easycontrol.app.helper;

import android.util.Base64;
import android.util.Log;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import top.saymzx.easycontrol.app.client.tools.AdbTools;
import top.saymzx.easycontrol.app.entity.AppData;
import top.saymzx.easycontrol.app.entity.Device;

/** Small dependency-free LAN HTTP bridge for browser and agent control. */
public final class HttpControlServer {
  public static final int PORT = 18080;
  private static final ExecutorService EXECUTOR = Executors.newCachedThreadPool();
  private static volatile boolean running;
  private static ServerSocket serverSocket;

  private HttpControlServer() {}

  public static synchronized void start() {
    if (running) return;
    running = true;
    new Thread(() -> {
      try {
        serverSocket = new ServerSocket(PORT);
        while (running) EXECUTOR.execute(new Connection(serverSocket.accept()));
      } catch (IOException ignored) {
      } finally {
        running = false;
      }
    }, "easycontrol-http").start();
  }

  public static synchronized void stop() {
    running = false;
    if (serverSocket != null) try { serverSocket.close(); } catch (IOException ignored) {}
  }

  private static Device device(String uuid) {
    if (uuid == null || AppData.dbHelper == null) return null;
    return AppData.dbHelper.getByUUID(uuid);
  }

  private static String field(String json, String name) {
    Matcher m = Pattern.compile("\\\"" + Pattern.quote(name) + "\\\"\\s*:\\s*(?:\\\"([^\\\"]*)\\\"|(-?\\d+(?:\\.\\d+)?))").matcher(json == null ? "" : json);
    return m.find() ? (m.group(1) != null ? m.group(1) : m.group(2)) : null;
  }

  private static int intField(String json, String name, int fallback) {
    try { return Integer.parseInt(field(json, name)); } catch (Exception ignored) { return fallback; }
  }

  private static String devicesJson() {
    StringBuilder out = new StringBuilder("[");
    ArrayList<Device> devices = AppData.dbHelper.getAll();
    for (int i = 0; i < devices.size(); i++) {
      Device d = devices.get(i);
      if (i > 0) out.append(',');
      out.append("{\"uuid\":\"").append(json(d.uuid)).append("\",\"name\":\"")
          .append(json(d.name)).append("\",\"address\":\"").append(json(d.address))
          .append("\",\"adbPort\":").append(d.adbPort).append('}');
    }
    return out.append(']').toString();
  }

  private static String json(String value) {
    return value == null ? "" : value.replace("\\", "\\\\").replace("\"", "\\\"");
  }

  private static byte[] screenshot(Device d) throws Exception {
    // base64 keeps binary PNG data intact across the ADB shell transport.
    String encoded = AdbTools.runCommand(d, "screencap -p | base64 -w 0").replaceAll("\\s+", "");
    return Base64.decode(encoded, Base64.DEFAULT);
  }

  private static void command(Device d, String cmd) throws Exception {
    AdbTools.runCommand(d, cmd);
  }

  private static String page() {
    return "<!doctype html><meta name=viewport content='width=device-width,initial-scale=1,maximum-scale=1,user-scalable=no,viewport-fit=cover'>"
      + "<title>EasyControl Web</title><style>body{font-family:sans-serif;margin:0;background:#111;color:#eee;overflow:hidden;overscroll-behavior:none}"
      + "header{height:54px;padding:8px;display:flex;gap:6px;align-items:center;background:#222;position:sticky;top:0;padding-top:max(8px,env(safe-area-inset-top))}select,button{font-size:16px;padding:7px;min-height:38px}select{min-width:0;flex:1}button{touch-action:manipulation}"
      + "#screen{width:100%;height:calc(100dvh - 54px);display:block;touch-action:none;object-fit:contain;user-select:none;-webkit-user-select:none;-webkit-touch-callout:none}"
      + "#msg{font-size:12px;color:#aaa;margin-left:8px}</style>"
      + "<header><select id=d></select><button onclick=key(4)>返回</button><button onclick=key(3)>主页</button><span id=msg></span></header>"
      + "<img id=screen><script>const d=document.getElementById('d'),s=document.getElementById('screen'),m=document.getElementById('msg');"
      + "async function load(){let a=await (await fetch('/api/devices')).json();d.innerHTML=a.map(x=>`<option value='${x.uuid}'>${x.name}</option>`).join('');refresh()}"
      + "async function refresh(){if(!d.value)return;s.src='/api/screenshot?uuid='+encodeURIComponent(d.value)+'&t='+Date.now()}"
      + "async function post(path,obj){let r=await fetch(path,{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify(Object.assign({uuid:d.value},obj))});m.textContent=await r.text();setTimeout(()=>m.textContent='',1200);refresh()}"
      + "function key(k){post('/api/key',{keycode:k})}let p=null;screen.onpointerdown=e=>{e.preventDefault();p=[e.clientX,e.clientY,Date.now()];screen.setPointerCapture(e.pointerId)};screen.onpointercancel=()=>p=null;screen.onpointerup=e=>{e.preventDefault();if(!p)return;let r=screen.getBoundingClientRect(),sx=Math.round((p[0]-r.left)*screen.naturalWidth/r.width),sy=Math.round((p[1]-r.top)*screen.naturalHeight/r.height),ex=Math.round((e.clientX-r.left)*screen.naturalWidth/r.width),ey=Math.round((e.clientY-r.top)*screen.naturalHeight/r.height),dt=Math.max(1,Date.now()-p[2]);if(Math.hypot(ex-sx,ey-sy)<12)post('/api/touch',{x:sx,y:sy});else post('/api/swipe',{x1:sx,y1:sy,x2:ex,y2:ey,duration:dt});p=null};d.onchange=refresh;load();setInterval(refresh,1500)</script>";
  }

  private static final class Connection implements Runnable {
    private final Socket socket;
    Connection(Socket socket) { this.socket = socket; }
    @Override public void run() {
      try {
        BufferedInputStream in = new BufferedInputStream(socket.getInputStream());
        String request = readHeaders(in);
        if (request == null) return;
        String[] first = request.split("\\r?\\n", 2)[0].split(" ");
        String method = first[0], target = first[1];
        int length = 0;
        Matcher lm = Pattern.compile("(?im)^content-length:\\s*(\\d+)").matcher(request);
        if (lm.find()) length = Integer.parseInt(lm.group(1));
        byte[] body = new byte[length];
        for (int n = 0; n < length;) { int x = in.read(body, n, length - n); if (x < 0) break; n += x; }
        handle(socket.getOutputStream(), method, target, new String(body, StandardCharsets.UTF_8));
      } catch (Exception e) {
        Log.e("EasyControlHttp", "request failed", e);
        try { sendText(socket.getOutputStream(), 500, e.getMessage() == null ? "request failed" : e.getMessage()); } catch (IOException ignored) {}
      } finally { try { socket.close(); } catch (IOException ignored) {} }
    }
  }

  private static String readHeaders(InputStream in) throws IOException {
    ByteArrayOutputStream b = new ByteArrayOutputStream(); int state = 0, c;
    while ((c = in.read()) >= 0) { b.write(c); if ((state == 0 && c == '\r') || (state == 2 && c == '\r')) state++; else if ((state == 1 && c == '\n') || (state == 3 && c == '\n')) state++; else state = 0; if (state == 4) break; }
    return b.size() == 0 ? null : b.toString("UTF-8");
  }

  private static void handle(OutputStream out, String method, String target, String body) throws Exception {
    String path = target, query = ""; int q = target.indexOf('?');
    if (q >= 0) { path = target.substring(0, q); query = target.substring(q + 1); }
    if (method.equals("GET") && path.equals("/")) { send(out, 200, "text/html; charset=utf-8", page().getBytes(StandardCharsets.UTF_8)); return; }
    if (method.equals("GET") && path.equals("/api/devices")) { sendText(out, 200, devicesJson()); return; }
    if (method.equals("GET") && path.equals("/api/screenshot")) {
      String uuid = param(query, "uuid"); Device d = device(uuid); if (d == null) { sendText(out, 404, "device not found"); return; }
      send(out, 200, "image/png", screenshot(d)); return;
    }
    String uuid = field(body, "uuid"); Device d = device(uuid); if (d == null) { sendText(out, 404, "device not found"); return; }
    if (!method.equals("POST")) { sendText(out, 405, "method not allowed"); return; }
    if (path.equals("/api/touch")) command(d, "input tap " + intField(body,"x",0) + " " + intField(body,"y",0));
    else if (path.equals("/api/swipe")) command(d, "input swipe " + intField(body,"x1",0) + " " + intField(body,"y1",0) + " " + intField(body,"x2",0) + " " + intField(body,"y2",0) + " " + intField(body,"duration",300));
    else if (path.equals("/api/key")) command(d, "input keyevent " + intField(body,"keycode",4));
    else { sendText(out, 404, "unknown endpoint"); return; }
    sendText(out, 200, "ok");
  }

  private static String param(String query, String key) throws Exception {
    for (String p : query.split("&")) { String[] a = p.split("=", 2); if (a.length == 2 && a[0].equals(key)) return URLDecoder.decode(a[1], "UTF-8"); }
    return null;
  }
  private static void sendText(OutputStream out, int code, String text) throws IOException { send(out, code, "text/plain; charset=utf-8", text.getBytes(StandardCharsets.UTF_8)); }
  private static void send(OutputStream out, int code, String type, byte[] data) throws IOException {
    String status = code == 200 ? "OK" : code == 404 ? "Not Found" : code == 405 ? "Method Not Allowed" : "Error";
    out.write(("HTTP/1.1 " + code + " " + status + "\r\nContent-Type: " + type + "\r\nContent-Length: " + data.length + "\r\nAccess-Control-Allow-Origin: *\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
    out.write(data); out.flush();
  }
}
