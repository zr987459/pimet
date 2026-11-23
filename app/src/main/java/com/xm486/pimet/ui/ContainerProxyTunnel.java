package com.xm486.pimet.ui;

import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 本地无认证 HTTP 代理隧道（仅监听 127.0.0.1 随机端口）：
 * <ul>
 *   <li>接收 Chromium / HttpURLConnection 发来的本地无认证 HTTP/CONNECT 请求；</li>
 *   <li>支持直连目标 HTTP / SOCKS5 代理（含账密认证，UTF-8）；</li>
 *   <li>支持经由前置代理 upstream（HTTP / SOCKS5）链式穿透到目标代理；</li>
 *   <li>SOCKS5 严格使用 ATYP=0x03 域名解析，不在本地对目标站域名做 DNS 解析；</li>
 *   <li>绝不向上游/WebView 回传 407，失败统一回 502（繁忙回 503）。</li>
 * </ul>
 */
public final class ContainerProxyTunnel implements AutoCloseable {

    private static final int MAX_HEADER_BYTES = 64 * 1024;
    private static final int CONNECT_TIMEOUT_MS = 10_000;
    private static final int HANDSHAKE_SO_TIMEOUT_MS = 15_000;
    private static final int PLAIN_HTTP_SO_TIMEOUT_MS = 60_000;
    private static final int RELAY_BUF_SIZE = 16 * 1024;

    private final ContainerEnv.ProxySpec spec;
    private final ServerSocket server;
    private final int port;
    private final ThreadPoolExecutor pool;
    private final ThreadPoolExecutor relayPool;
    private final Thread acceptThread;
    private final AtomicBoolean open = new AtomicBoolean(true);
    private final Set<Socket> activeSockets = ConcurrentHashMap.newKeySet();

    public ContainerProxyTunnel(ContainerEnv.ProxySpec spec) throws IOException {
        if (spec == null || !spec.isActive()) {
            throw new IllegalArgumentException("ProxySpec 必须为已启用的有效代理");
        }
        this.spec = spec;
        this.server = new ServerSocket(0, 64, InetAddress.getByAddress(new byte[]{127, 0, 0, 1}));
        this.port = this.server.getLocalPort();
        this.pool = new ThreadPoolExecutor(
                4,
                96,
                30L,
                TimeUnit.SECONDS,
                new SynchronousQueue<>(),
                r -> {
                    Thread t = new Thread(r, "pimet-proxy-worker");
                    t.setDaemon(true);
                    return t;
                }
        );
        this.relayPool = new ThreadPoolExecutor(
                0,
                96,
                30L,
                TimeUnit.SECONDS,
                new SynchronousQueue<>(),
                r -> {
                    Thread t = new Thread(r, "pimet-proxy-pump-" + this.port);
                    t.setDaemon(true);
                    return t;
                }
        );
        this.acceptThread = new Thread(this::acceptLoop, "pimet-proxy-accept-" + this.port);
        this.acceptThread.setDaemon(true);
        this.acceptThread.start();
    }

    public int getPort() {
        return port;
    }

    public boolean isOpen() {
        return open.get() && !server.isClosed();
    }

    public int activeSocketCount() {
        return activeSockets.size();
    }

    @Override
    public void close() {
        if (!open.compareAndSet(true, false)) return;
        try {
            server.close();
        } catch (Throwable ignored) {}
        for (Socket s : activeSockets) {
            closeQuietly(s);
        }
        activeSockets.clear();
        try {
            pool.shutdownNow();
        } catch (Throwable ignored) {}
        try {
            relayPool.shutdownNow();
        } catch (Throwable ignored) {}
        try {
            acceptThread.interrupt();
        } catch (Throwable ignored) {}
    }

    private void acceptLoop() {
        while (open.get() && !server.isClosed()) {
            Socket client = null;
            try {
                client = server.accept();
                if (!open.get()) {
                    closeQuietly(client);
                    break;
                }
                activeSockets.add(client);
                final Socket c = client;
                try {
                    pool.execute(() -> handleClient(c));
                } catch (RejectedExecutionException rex) {
                    try {
                        writeSimpleResponse(c.getOutputStream(), "503 Service Unavailable");
                    } catch (Throwable ignored) {}
                    closeAndUntrack(c);
                }
            } catch (Throwable t) {
                if (client != null) closeAndUntrack(client);
                if (!open.get() || server.isClosed()) break;
            }
        }
    }

    private void handleClient(Socket client) {
        Socket remote = null;
        boolean responseStarted = false;
        try {
            client.setSoTimeout(HANDSHAKE_SO_TIMEOUT_MS);
            client.setTcpNoDelay(true);
            InputStream cin = client.getInputStream();
            OutputStream cout = client.getOutputStream();

            byte[] rawHdr = readHttpHeaderBlock(cin);
            if (rawHdr == null || rawHdr.length == 0) {
                return;
            }
            String hdrText = new String(rawHdr, StandardCharsets.ISO_8859_1);
            String[] lines = splitHttpLines(hdrText);
            if (lines.length == 0 || lines[0].trim().isEmpty()) {
                writeSimpleResponse(cout, "400 Bad Request");
                return;
            }

            String[] reqParts = lines[0].trim().split("\\s+", 3);
            if (reqParts.length < 2) {
                writeSimpleResponse(cout, "400 Bad Request");
                return;
            }
            String method = reqParts[0].toUpperCase(Locale.ROOT);
            String rawTarget = reqParts[1];
            List<String> rawHeaders = new ArrayList<>();
            for (int i = 1; i < lines.length; i++) {
                if (!lines[i].isEmpty()) rawHeaders.add(lines[i]);
            }

            if ("CONNECT".equals(method)) {
                HostPort hp = parseConnectTarget(rawTarget);
                remote = openChainedToTargetProxy();
                if ("http".equals(spec.type)) {
                    sendHttpConnect(remote, hp.host, hp.port, spec.username, spec.password);
                } else {
                    socks5Connect(remote, spec.username, spec.password, hp.host, hp.port);
                }
                cout.write("HTTP/1.1 200 Connection Established\r\n\r\n".getBytes(StandardCharsets.ISO_8859_1));
                cout.flush();
                responseStarted = true;

                client.setSoTimeout(0);
                remote.setSoTimeout(0);
                relay(client, remote, true);
            } else {
                ParsedHttpTarget target = parseHttpRequestTarget(rawTarget, rawHeaders);
                remote = openChainedToTargetProxy();

                if (spec.upstream == null && "http".equals(spec.type)) {
                    // 无 upstream 且目标是 HTTP 代理：保留绝对 URI 转发给目标 HTTP 代理
                    String reqLine = method + " http://" + formatHostPort(target.host, target.port) + target.pathAndQuery + " HTTP/1.1\r\n";
                    byte[] outHdr = buildForwardHeaders(reqLine, rawHeaders, target, true, spec.hasAuth() ? basicAuthHeader(spec.username, spec.password) : null);
                    OutputStream rout = remote.getOutputStream();
                    rout.write(outHdr);
                    rout.flush();
                } else {
                    // 目标是 SOCKS5 或带 upstream 链式：先通过链式隧道连到目标站点 host:port，再发相对路径请求
                    if ("socks5".equals(spec.type)) {
                        socks5Connect(remote, spec.username, spec.password, target.host, target.port);
                    } else {
                        sendHttpConnect(remote, target.host, target.port, spec.username, spec.password);
                    }
                    String reqLine = method + " " + target.pathAndQuery + " HTTP/1.1\r\n";
                    byte[] outHdr = buildForwardHeaders(reqLine, rawHeaders, target, false, null);
                    OutputStream rout = remote.getOutputStream();
                    rout.write(outHdr);
                    rout.flush();
                }

                responseStarted = true;
                client.setSoTimeout(PLAIN_HTTP_SO_TIMEOUT_MS);
                remote.setSoTimeout(PLAIN_HTTP_SO_TIMEOUT_MS);
                relay(client, remote, false);
            }
        } catch (Throwable t) {
            if (!responseStarted && !client.isClosed()) {
                try {
                    writeSimpleResponse(client.getOutputStream(), "502 Bad Gateway");
                } catch (Throwable ignored) {}
            }
        } finally {
            closeAndUntrack(remote);
            closeAndUntrack(client);
        }
    }

    /** 建立到目标代理（spec.host:spec.port）的 TCP 连接；若有 upstream，先经由 upstream 链式打通 */
    private Socket openChainedToTargetProxy() throws IOException {
        if (spec.upstream == null) {
            return connectSocket(spec.host, spec.port);
        }
        ContainerEnv.ProxyEndpoint up = spec.upstream;
        Socket s = connectSocket(up.host, up.port);
        try {
            if ("http".equals(up.type)) {
                sendHttpConnect(s, spec.host, spec.port, up.username, up.password);
            } else {
                socks5Connect(s, up.username, up.password, spec.host, spec.port);
            }
            return s;
        } catch (Throwable t) {
            closeAndUntrack(s);
            if (t instanceof IOException) throw (IOException) t;
            throw new IOException("链式前置代理握手失败: " + t.getMessage(), t);
        }
    }

    private Socket connectSocket(String host, int port) throws IOException {
        if (!open.get()) throw new IOException("Tunnel closed");
        Socket s = new Socket();
        activeSockets.add(s);
        try {
            s.connect(new InetSocketAddress(host, port), CONNECT_TIMEOUT_MS);
            s.setSoTimeout(HANDSHAKE_SO_TIMEOUT_MS);
            s.setTcpNoDelay(true);
            return s;
        } catch (Throwable t) {
            closeAndUntrack(s);
            if (t instanceof IOException) throw (IOException) t;
            throw new IOException(t.getMessage(), t);
        }
    }

    private static void sendHttpConnect(Socket remote, String targetHost, int targetPort,
                                        String user, String pass) throws IOException {
        String authority = formatHostPort(targetHost, targetPort);
        StringBuilder sb = new StringBuilder();
        sb.append("CONNECT ").append(authority).append(" HTTP/1.1\r\n");
        sb.append("Host: ").append(authority).append("\r\n");
        if ((user != null && !user.isEmpty()) || (pass != null && !pass.isEmpty())) {
            sb.append("Proxy-Authorization: ").append(basicAuthHeader(user, pass)).append("\r\n");
        }
        sb.append("\r\n");
        OutputStream out = remote.getOutputStream();
        out.write(sb.toString().getBytes(StandardCharsets.ISO_8859_1));
        out.flush();

        byte[] respHdr = readHttpHeaderBlock(remote.getInputStream());
        if (respHdr == null || respHdr.length == 0) {
            throw new EOFException("HTTP CONNECT 上游无响应");
        }
        String respText = new String(respHdr, StandardCharsets.ISO_8859_1);
        String[] lines = splitHttpLines(respText);
        if (lines.length == 0) {
            throw new IOException("HTTP CONNECT 响应无效");
        }
        String[] parts = lines[0].trim().split("\\s+", 3);
        int code = -1;
        if (parts.length >= 2) {
            try {
                code = Integer.parseInt(parts[1]);
            } catch (NumberFormatException ignored) {}
        }
        if (code < 200 || code >= 300) {
            throw new IOException("HTTP CONNECT 失败: " + lines[0]);
        }
    }

    /**
     * RFC 1928 / RFC 1929 SOCKS5 握手：
     * 强制使用 ATYP=0x03 发送域名字节，严禁本地对目标网站域名调 InetAddress.getByName。
     */
    static void socks5Connect(Socket s, String user, String pass, String targetHost, int targetPort) throws IOException {
        if (targetHost == null || targetHost.isEmpty()) {
            throw new IOException("SOCKS5 目标主机为空");
        }
        byte[] hostBytes = targetHost.getBytes(StandardCharsets.UTF_8);
        if (hostBytes.length < 1 || hostBytes.length > 255) {
            throw new IOException("SOCKS5 域名长度不合法: " + hostBytes.length);
        }
        boolean hasAuth = (user != null && !user.isEmpty()) || (pass != null && !pass.isEmpty());
        InputStream in = s.getInputStream();
        OutputStream out = s.getOutputStream();

        if (hasAuth) {
            out.write(new byte[]{0x05, 0x02, 0x00, 0x02});
        } else {
            out.write(new byte[]{0x05, 0x01, 0x00});
        }
        out.flush();

        byte[] methodResp = readFully(in, 2);
        if ((methodResp[0] & 0xFF) != 0x05) {
            throw new IOException("SOCKS5 版本异常: " + (methodResp[0] & 0xFF));
        }
        int chosen = methodResp[1] & 0xFF;
        if (chosen == 0x02) {
            if (!hasAuth) throw new IOException("SOCKS5 要求账密认证但未配置凭据");
            byte[] ub = (user == null ? "" : user).getBytes(StandardCharsets.UTF_8);
            byte[] pb = (pass == null ? "" : pass).getBytes(StandardCharsets.UTF_8);
            if (ub.length > 255 || pb.length > 255) {
                throw new IOException("SOCKS5 用户名或密码超过 255 字节");
            }
            byte[] authReq = new byte[3 + ub.length + pb.length];
            authReq[0] = 0x01;
            authReq[1] = (byte) ub.length;
            System.arraycopy(ub, 0, authReq, 2, ub.length);
            authReq[2 + ub.length] = (byte) pb.length;
            System.arraycopy(pb, 0, authReq, 3 + ub.length, pb.length);
            out.write(authReq);
            out.flush();

            byte[] authResp = readFully(in, 2);
            if ((authResp[0] & 0xFF) != 0x01 || (authResp[1] & 0xFF) != 0x00) {
                throw new IOException("SOCKS5 认证失败: status=" + (authResp[1] & 0xFF));
            }
        } else if (chosen != 0x00) {
            throw new IOException("SOCKS5 不支持的认证方式: " + chosen);
        }

        byte[] req = new byte[4 + 1 + hostBytes.length + 2];
        req[0] = 0x05;
        req[1] = 0x01; // CONNECT
        req[2] = 0x00; // RSV
        req[3] = 0x03; // DOMAINNAME
        req[4] = (byte) hostBytes.length;
        System.arraycopy(hostBytes, 0, req, 5, hostBytes.length);
        req[5 + hostBytes.length] = (byte) ((targetPort >> 8) & 0xFF);
        req[6 + hostBytes.length] = (byte) (targetPort & 0xFF);
        out.write(req);
        out.flush();

        byte[] repHdr = readFully(in, 4);
        if ((repHdr[0] & 0xFF) != 0x05) {
            throw new IOException("SOCKS5 响应版本异常: " + (repHdr[0] & 0xFF));
        }
        int rep = repHdr[1] & 0xFF;
        if (rep != 0x00) {
            throw new IOException("SOCKS5 CONNECT 失败: rep=" + rep);
        }
        int atyp = repHdr[3] & 0xFF;
        if (atyp == 0x01) {
            readFully(in, 6);
        } else if (atyp == 0x03) {
            int len = readUnsignedByte(in);
            readFully(in, len + 2);
        } else if (atyp == 0x04) {
            readFully(in, 18);
        } else {
            throw new IOException("SOCKS5 无效 ATYP: " + atyp);
        }
    }

    /** 逐字节读取 HTTP 头部块到 \r\n\r\n，绝不使用 BufferedReader 预读吞掉后续数据 */
    static byte[] readHttpHeaderBlock(InputStream in) throws IOException {
        ByteArrayOutputStream buf = new ByteArrayOutputStream(512);
        int state = 0; // 0: normal, 1: \r, 2: \r\n, 3: \r\n\r
        int b;
        while ((b = in.read()) != -1) {
            buf.write(b);
            if (buf.size() > MAX_HEADER_BYTES) {
                throw new IOException("HTTP 头部过大 (>64KB)");
            }
            if (state == 0) {
                state = (b == '\r') ? 1 : 0;
            } else if (state == 1) {
                state = (b == '\n') ? 2 : (b == '\r' ? 1 : 0);
            } else if (state == 2) {
                state = (b == '\r') ? 3 : 0;
            } else {
                if (b == '\n') {
                    return buf.toByteArray();
                }
                state = (b == '\r') ? 1 : 0;
            }
        }
        return buf.size() == 0 ? null : throwEof();
    }

    private static byte[] throwEof() throws IOException {
        throw new EOFException("HTTP 头部未结束即遇到 EOF");
    }

    private static byte[] readFully(InputStream in, int len) throws IOException {
        byte[] b = new byte[len];
        int off = 0;
        while (off < len) {
            int n = in.read(b, off, len - off);
            if (n < 0) throw new EOFException("SOCKS5 流提前结束");
            off += n;
        }
        return b;
    }

    private static int readUnsignedByte(InputStream in) throws IOException {
        int b = in.read();
        if (b < 0) throw new EOFException("SOCKS5 流提前结束");
        return b;
    }

    private void relay(Socket client, Socket remote, boolean isConnect) {
        try {
            Future<?> upFuture = relayPool.submit(() -> pump(client, remote));
            pump(remote, client);
            if (!isConnect) {
                // 明文 HTTP 响应已完整 EOF：立即关闭双端 socket 唤醒上行读取线程，不再无谓等待 up.join(1000)
                closeAndUntrack(remote);
                closeAndUntrack(client);
            } else {
                try {
                    upFuture.get(1000, TimeUnit.MILLISECONDS);
                } catch (Throwable ignored) {}
            }
        } catch (Throwable ignored) {
            closeAndUntrack(remote);
            closeAndUntrack(client);
        } finally {
            closeAndUntrack(remote);
            closeAndUntrack(client);
        }
    }

    private static void pump(Socket src, Socket dst) {
        byte[] buf = new byte[RELAY_BUF_SIZE];
        try {
            InputStream in = src.getInputStream();
            OutputStream out = dst.getOutputStream();
            int n;
            while ((n = in.read(buf)) != -1) {
                out.write(buf, 0, n);
                out.flush();
            }
            try {
                dst.shutdownOutput();
            } catch (Throwable ignored) {}
        } catch (Throwable ignored) {
            try {
                dst.shutdownOutput();
            } catch (Throwable ignored2) {}
        }
    }

    private static byte[] buildForwardHeaders(String reqLine, List<String> rawHeaders,
                                              ParsedHttpTarget target, boolean viaHttpProxy,
                                              String proxyAuthHeader) {
        StringBuilder sb = new StringBuilder(512);
        sb.append(reqLine);
        boolean hasHost = false;
        for (String h : rawHeaders) {
            int colon = h.indexOf(':');
            if (colon <= 0) continue;
            String name = h.substring(0, colon).trim();
            String low = name.toLowerCase(Locale.ROOT);
            if ("proxy-authorization".equals(low)
                    || "proxy-connection".equals(low)
                    || "connection".equals(low)
                    || "keep-alive".equals(low)) {
                continue;
            }
            if (!viaHttpProxy && low.startsWith("proxy-")) {
                continue;
            }
            if ("host".equals(low)) hasHost = true;
            sb.append(h).append("\r\n");
        }
        if (!hasHost) {
            sb.append("Host: ").append(formatHostPort(target.host, target.port)).append("\r\n");
        }
        sb.append("Connection: close\r\n");
        if (viaHttpProxy) {
            sb.append("Proxy-Connection: close\r\n");
            if (proxyAuthHeader != null && !proxyAuthHeader.isEmpty()) {
                sb.append("Proxy-Authorization: ").append(proxyAuthHeader).append("\r\n");
            }
        }
        sb.append("\r\n");
        return sb.toString().getBytes(StandardCharsets.ISO_8859_1);
    }

    private static String basicAuthHeader(String user, String pass) {
        String raw = (user == null ? "" : user) + ":" + (pass == null ? "" : pass);
        return "Basic " + Base64.getEncoder().encodeToString(raw.getBytes(StandardCharsets.UTF_8));
    }

    private static String[] splitHttpLines(String headerBlock) {
        String trimmed = headerBlock;
        while (trimmed.endsWith("\r\n")) {
            trimmed = trimmed.substring(0, trimmed.length() - 2);
        }
        return trimmed.split("\r\n");
    }

    private static final class HostPort {
        final String host;
        final int port;
        HostPort(String host, int port) {
            this.host = host;
            this.port = port;
        }
    }

    private static final class ParsedHttpTarget {
        final String host;
        final int port;
        final String pathAndQuery;
        ParsedHttpTarget(String host, int port, String pathAndQuery) {
            this.host = host;
            this.port = port;
            this.pathAndQuery = pathAndQuery;
        }
    }

    private static HostPort parseConnectTarget(String authority) throws IOException {
        return parseAuthority(authority, 443);
    }

    private static ParsedHttpTarget parseHttpRequestTarget(String rawTarget, List<String> rawHeaders) throws IOException {
        String t = rawTarget == null ? "" : rawTarget.trim();
        int hash = t.indexOf('#');
        if (hash >= 0) t = t.substring(0, hash);

        if (t.regionMatches(true, 0, "http://", 0, 7)) {
            String rest = t.substring(7);
            int slash = rest.indexOf('/');
            int q = rest.indexOf('?');
            int pathStart = (slash >= 0 && q >= 0) ? Math.min(slash, q) : Math.max(slash, q);
            String authority = pathStart >= 0 ? rest.substring(0, pathStart) : rest;
            String pathAndQuery = pathStart >= 0 ? rest.substring(pathStart) : "/";
            if (pathAndQuery.startsWith("?")) pathAndQuery = "/" + pathAndQuery;
            HostPort hp = parseAuthority(authority, 80);
            return new ParsedHttpTarget(hp.host, hp.port, pathAndQuery);
        }

        // 兜底：若收到相对路径，则从 Host 头取目标主机与端口
        String hostHdr = null;
        for (String h : rawHeaders) {
            int c = h.indexOf(':');
            if (c > 0 && "host".equalsIgnoreCase(h.substring(0, c).trim())) {
                hostHdr = h.substring(c + 1).trim();
                break;
            }
        }
        if (hostHdr == null || hostHdr.isEmpty()) {
            throw new IOException("明文 HTTP 请求缺少绝对 URI 与 Host 头: " + rawTarget);
        }
        HostPort hp = parseAuthority(hostHdr, 80);
        String pq = t.isEmpty() ? "/" : (t.startsWith("/") ? t : "/" + t);
        return new ParsedHttpTarget(hp.host, hp.port, pq);
    }

    private static HostPort parseAuthority(String authority, int defaultPort) throws IOException {
        String a = authority == null ? "" : authority.trim();
        if (a.isEmpty()) throw new IOException("目标地址为空");
        if (a.startsWith("[")) {
            int close = a.indexOf(']');
            if (close <= 1) throw new IOException("无效 IPv6 目标: " + authority);
            String host = a.substring(1, close).trim();
            String tail = a.substring(close + 1).trim();
            int port = defaultPort;
            if (tail.startsWith(":")) {
                port = parsePort(tail.substring(1), authority);
            } else if (!tail.isEmpty()) {
                throw new IOException("无效目标端口: " + authority);
            }
            return new HostPort(host, port);
        }
        int colon = a.lastIndexOf(':');
        if (colon > 0 && a.indexOf(':') == colon) {
            String host = a.substring(0, colon).trim();
            int port = parsePort(a.substring(colon + 1).trim(), authority);
            return new HostPort(host, port);
        }
        return new HostPort(a, defaultPort);
    }

    private static int parsePort(String s, String raw) throws IOException {
        try {
            int p = Integer.parseInt(s);
            if (p >= 1 && p <= 65535) return p;
        } catch (NumberFormatException ignored) {}
        throw new IOException("无效端口: " + raw);
    }

    private static String formatHostPort(String host, int port) {
        if (host.contains(":") && !host.startsWith("[")) {
            return "[" + host + "]:" + port;
        }
        return host + ":" + port;
    }

    private static void writeSimpleResponse(OutputStream out, String status) throws IOException {
        String resp = "HTTP/1.1 " + status + "\r\nConnection: close\r\nContent-Length: 0\r\n\r\n";
        out.write(resp.getBytes(StandardCharsets.ISO_8859_1));
        out.flush();
    }

    private void closeAndUntrack(Socket s) {
        if (s == null) return;
        activeSockets.remove(s);
        closeQuietly(s);
    }

    private static void closeQuietly(Socket s) {
        if (s == null) return;
        try {
            s.close();
        } catch (Throwable ignored) {}
    }
}
