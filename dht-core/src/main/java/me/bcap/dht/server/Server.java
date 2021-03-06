package me.bcap.dht.server;

import java.io.IOException;
import java.io.InputStream;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.OutputStream;
import java.math.BigInteger;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import org.apache.log4j.Logger;

import me.bcap.dht.message.request.FindNodeRequest;
import me.bcap.dht.message.request.FindValueRequest;
import me.bcap.dht.message.request.PingRequest;
import me.bcap.dht.message.request.Request;
import me.bcap.dht.message.request.StoreRequest;
import me.bcap.dht.message.response.PingResponse;
import me.bcap.dht.message.response.Response;
import me.bcap.dht.node.Contact;
import me.bcap.dht.node.Identifier;
import me.bcap.dht.node.Node;
import me.bcap.dht.server.handler.FindNodeRequestHandler;
import me.bcap.dht.server.handler.FindValueRequestHandler;
import me.bcap.dht.server.handler.PingRequestHandler;
import me.bcap.dht.server.handler.RequestHandler;
import me.bcap.dht.server.handler.RequestHandlerException;
import me.bcap.dht.server.handler.StoreRequestHandler;

public class Server extends Thread implements Runnable {

	private static final Logger logger = Logger.getLogger(Server.class);

	public static final int DEFAULT_BACKLOG_SIZE = 20;
	public static final int DEFAULT_MINIMUM_POOL_SIZE = 1;
	public static final int DEFAULT_MAXIMUM_POOL_SIZE = 30;
	public static final long DEFAULT_POLL_THREAD_ALIVE_TIME = 60000;

	private static int SERVER_COUNTER = 0;
	
	private Map<Class<? extends Request>, RequestHandler> handlers;
	private Map<Identifier, Node> nodes;

	private InetAddress ip;
	private int port;
	private int backlogSize;
	private int minimumPoolSize;
	private int maximumPoolSize;
	private long poolThreadAliveTime;
	
	private Server serverRef = this;

	private ServerSocket serverSocket;
	private ThreadPoolExecutor workerThreadPool;

	private boolean hasToRun = true;
	private boolean running = false;
	
	private CountDownLatch startingLatch = new CountDownLatch(1);
	
	public Server(int port) {
		this(null, port);
	}
	
	public Server(InetAddress ip, int port) {
		this(ip, port, DEFAULT_BACKLOG_SIZE, DEFAULT_MINIMUM_POOL_SIZE, DEFAULT_MAXIMUM_POOL_SIZE, DEFAULT_POLL_THREAD_ALIVE_TIME);
	}

	public Server(InetAddress ip, int port, int backlogSize, int minimumPoolSize, int maximumPoolSize, long poolThreadAliveTime) {
		if(ip == null) {
			try {
				ip = Inet4Address.getByName(null);
			} catch (UnknownHostException e) {
				String msg = "UnknowHostException should never occur while trying to get the loopback INetAddress";
				logger.fatal(msg, e);
				throw new RuntimeException(msg, e);
			}
		}
		
		this.ip = ip;
		this.port = port;
		this.backlogSize = backlogSize;
		this.minimumPoolSize = minimumPoolSize;
		this.maximumPoolSize = maximumPoolSize;
		this.poolThreadAliveTime = poolThreadAliveTime;
		this.handlers = new ConcurrentHashMap<Class<? extends Request>, RequestHandler>();
		this.nodes = new ConcurrentHashMap<Identifier, Node>();
		this.setName("Server-" + SERVER_COUNTER++);
	}

	@Override
	public void run() {
		running = true;
		
		logger.info("Starting server on adddress " + ip + ":" + port);

		addShutdownHook();

		createWorkerThreadPool();
			
		try {
			logger.debug("Opening socket on address " + ip + ":" + port + " with a message backlog of size " + backlogSize);
			serverSocket = new ServerSocket(port, backlogSize, ip);

			logger.info("Server started, waiting for connections");
			
			startingLatch.countDown();
			
			while (hasToRun) {
				try {
					Socket socket = serverSocket.accept();
					logger.info("Incoming connection from " + socket.getInetAddress() + ":" + socket.getPort());
					Worker worker = new Worker(socket);
					logger.debug("Submiting request to a new worker in the pool (active/size: " + workerThreadPool.getActiveCount() + "/" + workerThreadPool.getPoolSize() + ")");
					this.workerThreadPool.submit(worker);
				} catch (IOException e) {
					// when the server is shutting down a SocketException is generated as the socket is closed
					if (!(e instanceof SocketException && e.getMessage().equals("Socket closed") && !hasToRun))
						logger.error("IOException occured while trying to accept new connections", e);
				}
			}

		} catch (IOException e) {
			logger.fatal("Could not create main server socket!", e);
		}
		
		running = false;
	}
	
	public synchronized void start() {
		super.start();
		
		try {
			startingLatch.await();
		} catch (InterruptedException e) {
			logger.warn("Interrupted while waiting for server start", e);
		}
	}

	class Worker implements Runnable {

		private Socket socket;

		public Worker(Socket socket) {
			this.socket = socket;
		}

		@Override
		public void run() {
			ObjectInputStream inStream = null;
			ObjectOutputStream outStream = null;
			try {
				try {
					inStream = new ObjectInputStream(socket.getInputStream());
				} catch (IOException e) {
					logger.error("IOException occured while trying to open the socket inputStream");
					throw e;
				}

				try {
					outStream = new ObjectOutputStream(socket.getOutputStream());
				} catch (IOException e) {
					logger.error("IOException occured while trying to open the socket outputStream");
					throw e;
				}

				Object readObj = null;

				try {
					readObj = inStream.readObject();
				} catch (IOException e) {
					logger.error("IOException occured while trying to read the object from the socket");
					throw e;
				} catch (ClassNotFoundException e) {
					logger.error("ClassNotFoundException occured while trying to read object from the socket");
					throw e;
				}

				if (readObj instanceof Request) {
					Request request = (Request) readObj;

					logger.debug("Received request: " + request);

					RequestHandler handler = handlers.get(request.getClass());
					if (handler == null)
						throw new IllegalArgumentException("Received request cannot be handled by this server as no handler was found for type " + request.getClass());

					try {
						try {
							Response response = handler.handle(serverRef, request);
							logger.debug("Writing the response object back to the client: " + response);
							outStream.writeObject(response);
						} catch (RequestHandlerException e) {
							logger.warn("RequestHandlerException occured while trying to handle the request, sending an error with same message and with no stack back to the client", e);
							outStream.writeObject(new ServerException(e));
						}
					} catch (IOException e) {
						logger.error("IOException occured while trying to write the response object back to the client");
						throw e;
					}

				} else {
					logger.warn("Object read from the socket is of an unsupported type (not instance of " + Request.class + "): " + readObj.getClass());
				}

			} catch (Exception e) {
				logger.error(null, e);
			} finally {
				closeResources(socket, inStream, outStream);
			}
		}

		private void closeResources(Socket socket, InputStream inputStream, OutputStream outputStream) {
			if (inputStream != null) {
				try {
					inputStream.close();
				} catch (IOException e) {
					logger.error("Error while trying to close the inputstream " + inputStream, e);
				}
			}

			if (outputStream != null) {
				try {
					outputStream.close();
				} catch (IOException e) {
					logger.error("Error while trying to close the outputStream " + outputStream, e);
				}
			}

			if (socket != null) {
				try {
					socket.close();
				} catch (IOException e) {
					logger.error("Error while trying to close the socket " + socket, e);
				}
			}
		}
	}

	public void shutdown() {
		if (running) {
			logger.info("Shutting down server " + this.getName());
			
			this.hasToRun = false;
			
			if (this.serverSocket != null) {
				try {
					this.serverSocket.close();
				} catch (IOException e) {
					logger.error("IOException while trying to close the server main socket", e);
				}
			}
			
			if(workerThreadPool != null)
				workerThreadPool.shutdown();
			
			logger.debug("Server " + this.getName() + " successfully shutted down");
		}
	}

	private void addShutdownHook() {
		logger.debug("Adding server shutdown hook");
		final Server thisRef = this;
		Runtime.getRuntime().addShutdownHook(new Thread() {
			public void run() {
				thisRef.shutdown();
			}
		});
	}
	
	public void addDefaultHandlers() {
		this.handlers.put(PingRequest.class, new PingRequestHandler());
		this.handlers.put(StoreRequest.class, new StoreRequestHandler());
		this.handlers.put(FindNodeRequest.class, new FindNodeRequestHandler());
		this.handlers.put(FindValueRequest.class, new FindValueRequestHandler());
	}
	
	public boolean isRunning() {
		return running;
	}

	private void createWorkerThreadPool() {
		logger.debug("Creating a worker thread pool with size ranging from " + minimumPoolSize + " to " + maximumPoolSize + " and with a thread alive timeout of " + poolThreadAliveTime + "ms");
		workerThreadPool = new ThreadPoolExecutor(minimumPoolSize, maximumPoolSize, poolThreadAliveTime, TimeUnit.MILLISECONDS, new ArrayBlockingQueue<Runnable>(10));
	}

	public void addHandler(Class<? extends Request> requestClass, RequestHandler handler) {
		this.handlers.put(requestClass, handler);
	}

	public RequestHandler removeHandler(Class<? extends Request> requestClass) {
		return this.handlers.remove(requestClass);
	}

	public Collection<Class<? extends Request>> getHandledTypes() {
		return new ArrayList<Class<? extends Request>>(handlers.keySet());
	}

	public void addNode(Node node) {
		this.nodes.put(node.asIdentifier(), node);
	}

	public Node removeNode(Node node) {
		return this.nodes.remove(node.asIdentifier());
	}
	
	public Node getNode(Identifier id) {
		return this.nodes.get(id.asIdentifier());
	}

	public Collection<Node> getNodes() {
		return new ArrayList<Node>(nodes.values());
	}

	public int getBacklogSize() {
		return backlogSize;
	}

	public int getMinimumPoolSize() {
		return minimumPoolSize;
	}
	
	public int getMaximumPoolSize() {
		return maximumPoolSize;
	}
	
	public long getPoolThreadAliveTime() {
		return poolThreadAliveTime;
	}
	
	public ThreadPoolExecutor getWorkerThreadPool() {
		return workerThreadPool;
	}
	
	public InetAddress getIp() {
		return ip;
	}
	
	public int getPort() {
		return port;
	}

	public void setBacklogSize(int backlogSize) {
		if(this.isRunning())
			throw new IllegalStateException("Cannot change the server backlogSize as the server is already running");
		this.backlogSize = backlogSize;
	}

	public void setMinimumPoolSize(int minimumPoolSize) {
		if(this.isRunning())
			throw new IllegalStateException("Cannot change the server minimumPoolSize as the server is already running");
		this.minimumPoolSize = minimumPoolSize;
	}

	public void setMaximumPoolSize(int maximumPoolSize) {
		if(this.isRunning())
			throw new IllegalStateException("Cannot change the server maximumPoolSize as the server is already running");
		this.maximumPoolSize = maximumPoolSize;
	}

	public void setPoolThreadAliveTime(long poolThreadAliveTime) {
		if(this.isRunning())
			throw new IllegalStateException("Cannot change the server poolThreadAliveTime as the server is already running");
		this.poolThreadAliveTime = poolThreadAliveTime;
	}

	public void setIp(InetAddress ip) {
		if(this.isRunning())
			throw new IllegalStateException("Cannot change the server ip as the server is already running");
		this.ip = ip;
	}
	
	public void setPort(int port) {
		if(this.isRunning())
			throw new IllegalStateException("Cannot change the server port as the server is already running");
		this.port = port;
	}
}
