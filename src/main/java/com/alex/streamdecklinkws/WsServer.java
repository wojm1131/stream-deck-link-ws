package com.alex.streamdecklinkws;

import java.io.BufferedInputStream;
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
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;
import lombok.extern.slf4j.Slf4j;

/**
 * A minimal, hand-rolled RFC 6455 WebSocket server: no dependencies
 * beyond the JDK, bound to the loopback address only.
 *
 * The server is broadcast-only by design. It completes the opening
 * handshake, sends text frames, and answers ping with pong and close
 * with close — but it never acts on the content of any frame a client
 * sends. There is no command channel: received text/binary payloads
 * are read off the socket and discarded.
 */
@Slf4j
final class WsServer
{
	private static final String WS_GUID = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11";
	private static final int MAX_HEADER_BYTES = 16 * 1024;
	private static final int MAX_FRAME_BYTES = 1024 * 1024;

	private final int[] ports;
	private final Supplier<List<String>> snapshotSupplier;
	private final Set<ClientConn> clients = ConcurrentHashMap.newKeySet();

	private volatile boolean running;
	private ServerSocket serverSocket;
	private Thread acceptThread;
	private volatile int boundPort = -1;

	WsServer(int[] ports, Supplier<List<String>> snapshotSupplier)
	{
		this.ports = ports;
		this.snapshotSupplier = snapshotSupplier;
	}

	/** Binds the first free port, in order. Returns the bound port, or -1 if none worked. */
	synchronized int start()
	{
		if (running)
		{
			return boundPort;
		}
		for (int port : ports)
		{
			try
			{
				ServerSocket ss = new ServerSocket();
				ss.setReuseAddress(true);
				ss.bind(new InetSocketAddress(InetAddress.getLoopbackAddress(), port));
				serverSocket = ss;
				boundPort = port;
				running = true;
				acceptThread = new Thread(this::acceptLoop, "streamdeck-link-ws-accept");
				acceptThread.setDaemon(true);
				acceptThread.start();
				log.info("Stream Deck Link WS listening on 127.0.0.1:{}", port);
				return port;
			}
			catch (IOException e)
			{
				log.debug("Stream Deck Link WS could not bind port {}", port, e);
			}
		}
		log.warn("Stream Deck Link WS could not bind any of its ports");
		return -1;
	}

	synchronized void stop()
	{
		running = false;
		boundPort = -1;
		if (serverSocket != null)
		{
			try
			{
				serverSocket.close();
			}
			catch (IOException ignored)
			{
			}
			serverSocket = null;
		}
		for (ClientConn client : new ArrayList<>(clients))
		{
			client.closeQuietly();
		}
		clients.clear();
	}

	int getBoundPort()
	{
		return boundPort;
	}

	/** Sends one text frame to every connected client; dead clients are dropped. */
	void broadcast(String message)
	{
		if (clients.isEmpty())
		{
			return;
		}
		byte[] frame = encodeTextFrame(message);
		for (ClientConn client : clients)
		{
			if (!client.sendFrame(frame))
			{
				clients.remove(client);
				client.closeQuietly();
			}
		}
	}

	private void acceptLoop()
	{
		while (running)
		{
			try
			{
				Socket socket = serverSocket.accept();
				socket.setTcpNoDelay(true);
				ClientConn conn = new ClientConn(socket);
				Thread t = new Thread(() -> serve(conn), "streamdeck-link-ws-client");
				t.setDaemon(true);
				t.start();
			}
			catch (IOException e)
			{
				if (running)
				{
					log.debug("Stream Deck Link WS accept failed", e);
				}
				return;
			}
		}
	}

	private void serve(ClientConn conn)
	{
		try
		{
			if (!handshake(conn))
			{
				conn.closeQuietly();
				return;
			}
			clients.add(conn);
			// A newly connected client gets the full current state first;
			// after that it only receives changes, like everyone else.
			for (String payload : snapshotSupplier.get())
			{
				if (!conn.sendFrame(encodeTextFrame(payload)))
				{
					throw new EOFException("client went away during snapshot");
				}
			}
			readLoop(conn);
		}
		catch (IOException e)
		{
			log.debug("Stream Deck Link WS client disconnected: {}", e.getMessage());
		}
		finally
		{
			clients.remove(conn);
			conn.closeQuietly();
		}
	}

	/** Reads and discards client frames, answering only ping and close. */
	private void readLoop(ClientConn conn) throws IOException
	{
		InputStream in = conn.input;
		for (;;)
		{
			int b0 = in.read();
			if (b0 == -1)
			{
				throw new EOFException("end of stream");
			}
			int b1 = in.read();
			if (b1 == -1)
			{
				throw new EOFException("end of stream");
			}
			int opcode = b0 & 0x0F;
			boolean masked = (b1 & 0x80) != 0;
			long length = b1 & 0x7F;
			if (length == 126)
			{
				length = (readByte(in) << 8) | readByte(in);
			}
			else if (length == 127)
			{
				length = 0;
				for (int i = 0; i < 8; i++)
				{
					length = (length << 8) | readByte(in);
				}
			}
			if (length < 0 || length > MAX_FRAME_BYTES)
			{
				throw new IOException("frame too large: " + length);
			}
			byte[] maskKey = masked ? readFully(in, 4) : null;
			byte[] payload = readFully(in, (int) length);
			if (maskKey != null)
			{
				for (int i = 0; i < payload.length; i++)
				{
					payload[i] = (byte) (payload[i] ^ maskKey[i & 3]);
				}
			}
			switch (opcode)
			{
				case 0x8: // close
					conn.sendFrame(encodeFrame(0x8, new byte[0]));
					return;
				case 0x9: // ping -> pong, echoing the payload
					if (!conn.sendFrame(encodeFrame(0xA, payload)))
					{
						throw new EOFException("client went away on pong");
					}
					break;
				default:
					// 0x0 continuation, 0x1 text, 0x2 binary, 0xA pong:
					// content is deliberately ignored. This server takes
					// no commands from clients, ever.
					break;
			}
		}
	}

	private boolean handshake(ClientConn conn) throws IOException
	{
		String headers = readHeaders(conn.input);
		if (headers == null)
		{
			return false;
		}
		String key = null;
		for (String line : headers.split("\r\n"))
		{
			int colon = line.indexOf(':');
			if (colon > 0 && line.substring(0, colon).trim().equalsIgnoreCase("Sec-WebSocket-Key"))
			{
				key = line.substring(colon + 1).trim();
			}
		}
		if (key == null || key.isEmpty())
		{
			return false;
		}
		String accept;
		try
		{
			MessageDigest sha1 = MessageDigest.getInstance("SHA-1");
			accept = Base64.getEncoder().encodeToString(
				sha1.digest((key + WS_GUID).getBytes(StandardCharsets.UTF_8)));
		}
		catch (NoSuchAlgorithmException e)
		{
			return false;
		}
		String response = "HTTP/1.1 101 Switching Protocols\r\n"
			+ "Upgrade: websocket\r\n"
			+ "Connection: Upgrade\r\n"
			+ "Sec-WebSocket-Accept: " + accept + "\r\n"
			+ "\r\n";
		conn.output.write(response.getBytes(StandardCharsets.UTF_8));
		conn.output.flush();
		return true;
	}

	private static String readHeaders(InputStream in) throws IOException
	{
		ByteArrayOutputStream buf = new ByteArrayOutputStream();
		int matched = 0;
		int b;
		while ((b = in.read()) != -1)
		{
			buf.write(b);
			if (buf.size() > MAX_HEADER_BYTES)
			{
				return null;
			}
			// Track the \r\n\r\n terminator.
			if ((matched == 0 || matched == 2) && b == '\r')
			{
				matched++;
			}
			else if ((matched == 1 || matched == 3) && b == '\n')
			{
				matched++;
				if (matched == 4)
				{
					return buf.toString(StandardCharsets.UTF_8);
				}
			}
			else
			{
				matched = b == '\r' ? 1 : 0;
			}
		}
		return null;
	}

	private static int readByte(InputStream in) throws IOException
	{
		int b = in.read();
		if (b == -1)
		{
			throw new EOFException("end of stream");
		}
		return b;
	}

	private static byte[] readFully(InputStream in, int n) throws IOException
	{
		byte[] data = new byte[n];
		int off = 0;
		while (off < n)
		{
			int read = in.read(data, off, n - off);
			if (read == -1)
			{
				throw new EOFException("end of stream");
			}
			off += read;
		}
		return data;
	}

	private static byte[] encodeTextFrame(String message)
	{
		return encodeFrame(0x1, message.getBytes(StandardCharsets.UTF_8));
	}

	private static byte[] encodeFrame(int opcode, byte[] payload)
	{
		ByteArrayOutputStream out = new ByteArrayOutputStream(payload.length + 10);
		out.write(0x80 | opcode);
		if (payload.length < 126)
		{
			out.write(payload.length);
		}
		else if (payload.length <= 0xFFFF)
		{
			out.write(126);
			out.write((payload.length >>> 8) & 0xFF);
			out.write(payload.length & 0xFF);
		}
		else
		{
			out.write(127);
			long len = payload.length;
			for (int i = 7; i >= 0; i--)
			{
				out.write((int) (len >>> (8 * i)) & 0xFF);
			}
		}
		out.write(payload, 0, payload.length);
		return out.toByteArray();
	}

	private static final class ClientConn
	{
		final Socket socket;
		final InputStream input;
		final OutputStream output;
		private final Object sendLock = new Object();

		ClientConn(Socket socket) throws IOException
		{
			this.socket = socket;
			this.input = new BufferedInputStream(socket.getInputStream());
			this.output = socket.getOutputStream();
		}

		boolean sendFrame(byte[] frame)
		{
			synchronized (sendLock)
			{
				try
				{
					output.write(frame);
					output.flush();
					return true;
				}
				catch (IOException e)
				{
					return false;
				}
			}
		}

		void closeQuietly()
		{
			try
			{
				socket.close();
			}
			catch (IOException ignored)
			{
			}
		}
	}
}
