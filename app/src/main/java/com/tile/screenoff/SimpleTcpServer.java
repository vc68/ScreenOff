package com.tile.screenoff;

import android.util.Log;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;

/**
 * 极简的 HTTP 服务器：单线程 accept + 逐个连接处理。
 * <p>
 * 相比旧实现（每个响应都新建线程、每次都 restart 一遍、每次 read 都申请 1MB 缓冲区），
 * 这里做了三件事：
 * 1. 只有一个 accept 线程，不再有线程风暴和 ServerSocket 泄漏；
 * 2. 请求缓冲区固定大小并复用，避免频繁 GC；
 * 3. 每个连接都有超时，慢客户端不会把服务卡死。
 */
public class SimpleTcpServer {

    public interface RequestHandler {
        /**
         * @param request 原始请求字节（已读到请求头结束）
         * @return 完整响应字节；返回 null 时服务端返回 503
         */
        byte[] handle(byte[] request);
    }

    private static final String TAG = "ScreenOff";
    private static final int MAX_REQUEST_SIZE = 16 * 1024;
    private static final int SOCKET_TIMEOUT_MS = 5000;
    private static final byte[] HEADER_END = {'\r', '\n', '\r', '\n'};

    private final RequestHandler handler;
    private final int requestedPort;

    private ServerSocket serverSocket;
    private volatile boolean running;
    private volatile Socket clientSocket;
    private Thread acceptThread;

    public SimpleTcpServer(RequestHandler handler, int port) {
        this.handler = handler;
        this.requestedPort = port;
    }

    public int getPort() {
        ServerSocket ss = serverSocket;
        return ss == null ? requestedPort : ss.getLocalPort();
    }

    /** @return 是否成功监听 */
    public boolean start() {
        if (running) return true;
        try {
            ServerSocket ss = new ServerSocket();
            ss.setReuseAddress(true);
            ss.bind(new InetSocketAddress(requestedPort), 8);
            serverSocket = ss;
        } catch (IOException e) {
            Log.w(TAG, "http server bind failed on port " + requestedPort, e);
            return false;
        }
        running = true;
        acceptThread = new Thread(this::acceptLoop, "screenoff-http");
        acceptThread.setDaemon(true);
        acceptThread.start();
        return true;
    }

    private void acceptLoop() {
        while (running) {
            Socket socket = null;
            try {
                socket = serverSocket.accept();
                clientSocket = socket;
                handleConnection(socket);
            } catch (IOException e) {
                // stop() 关闭 ServerSocket 时会走到这里，属于正常退出
                if (running) Log.w(TAG, "accept failed", e);
            } catch (Throwable t) {
                Log.w(TAG, "handle connection failed", t);
            } finally {
                clientSocket = null;
                closeQuietly(socket);
            }
        }
    }

    private void handleConnection(Socket socket) throws IOException {
        socket.setSoTimeout(SOCKET_TIMEOUT_MS);
        socket.setTcpNoDelay(true);

        InputStream in = new BufferedInputStream(socket.getInputStream(), 1024);
        byte[] request = readRequest(in);
        if (request == null) return;

        byte[] response = handler.handle(request);
        if (response == null) {
            response = Http.plain(503, "busy");
        }

        OutputStream out = socket.getOutputStream();
        out.write(response);
        out.flush();
    }

    /** 读到请求头结束（或客户端关闭）为止。 */
    private byte[] readRequest(InputStream in) throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream(512);
        byte[] chunk = new byte[1024];
        while (buffer.size() < MAX_REQUEST_SIZE) {
            int read = in.read(chunk);
            if (read < 0) break;
            buffer.write(chunk, 0, read);
            byte[] data = buffer.toByteArray();
            if (indexOf(data, HEADER_END) >= 0) return data;
        }
        return buffer.size() == 0 ? null : buffer.toByteArray();
    }

    private static int indexOf(byte[] data, byte[] target) {
        outer:
        for (int i = 0; i <= data.length - target.length; i++) {
            for (int j = 0; j < target.length; j++) {
                if (data[i + j] != target[j]) continue outer;
            }
            return i;
        }
        return -1;
    }

    public void stop() {
        running = false;
        closeQuietly(serverSocket);
        serverSocket = null;
        closeQuietly(clientSocket);
        clientSocket = null;
        Thread t = acceptThread;
        acceptThread = null;
        if (t != null) t.interrupt();
    }

    private static void closeQuietly(java.io.Closeable closeable) {
        if (closeable == null) return;
        try {
            closeable.close();
        } catch (IOException ignored) {
        }
    }
}
