/*
 * Copyright (c) 2011-2014 The original author or authors
 * ------------------------------------------------------
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * and Apache License v2.0 which accompanies this distribution.
 *
 *     The Eclipse Public License is available at
 *     http://www.eclipse.org/legal/epl-v10.html
 *
 *     The Apache License v2.0 is available at
 *     http://www.opensource.org/licenses/apache2.0.php
 *
 * You may elect to redistribute this code under either of these licenses.
 */

package io.vertx.core.http.impl;

import io.vertx.core.Context;
import io.vertx.core.Handler;
import io.vertx.core.http.ConnectionPoolTooBusyException;
import io.vertx.core.impl.ContextImpl;
import io.vertx.core.logging.Logger;
import io.vertx.core.logging.LoggerFactory;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;

/**
 *
 * @author <a href="http://tfox.org">Tim Fox</a>
 */
public abstract class ConnectionManager {

  private static final Logger log = LoggerFactory.getLogger(ConnectionManager.class);

  private final int maxSockets;
  private final boolean keepAlive;
  private final boolean pipelining;
  private final int maxWaitQueueSize;
  private final Map<TargetAddress, ConnQueue> connQueues = new ConcurrentHashMap<>();

  ConnectionManager(int maxSockets, boolean keepAlive, boolean pipelining, int maxWaitQueueSize) {
    this.maxSockets = maxSockets;
    this.keepAlive = keepAlive;
    this.pipelining = pipelining;
    this.maxWaitQueueSize = maxWaitQueueSize;
  }

  public void getConnection(int port, String host, Handler<ClientConnection> handler, Handler<Throwable> connectionExceptionHandler,
                            ContextImpl context, BooleanSupplier canceled) {
    if (!keepAlive && pipelining) {
      connectionExceptionHandler.handle(new IllegalStateException("Cannot have pipelining with no keep alive"));
    } else {
      TargetAddress address = new TargetAddress(host, port);
      ConnQueue connQueue = connQueues.get(address);
      if (connQueue == null) {
        connQueue = new ConnQueue(address);
        ConnQueue prev = connQueues.putIfAbsent(address, connQueue);
        if (prev != null) {
          connQueue = prev;
        }
      }
      connQueue.getConnection(handler, connectionExceptionHandler, context, canceled);
    }
  }

  protected abstract void connect(String host, int port, Handler<ClientConnection> connectHandler, Handler<Throwable> connectErrorHandler, ContextImpl context,
                                  ConnectionLifeCycleListener listener);

  public void close() {
    for (ConnQueue queue: connQueues.values()) {
      queue.closeAllConnections();
    }
    connQueues.clear();
  }

  private class ConnQueue implements ConnectionLifeCycleListener {

    private final TargetAddress address;
    private final Deque<Waiter> waiters = new ArrayDeque<>();
    private final Set<ClientConnection> allConnections = new HashSet<>();
    private final Queue<ClientConnection> availableConnections = new ArrayDeque<>();
    private int connCount;

    ConnQueue(TargetAddress address) {
      this.address = address;
    }

    public synchronized void getConnection(Handler<ClientConnection> handler, Handler<Throwable> connectionExceptionHandler,
                                           ContextImpl context, BooleanSupplier canceled) {


      // Find a connection in the pool with the same context
      ClientConnection contextConn = null;
      if (availableConnections.size() > 0 && availableConnections.peek().getContext() == context) {
        // When the client is used by single context it chose this path
        contextConn = availableConnections.poll();
      } else if (availableConnections.size() > 0) {
        // Otherwise scan the pool to find one
        for (Iterator<ClientConnection> i = availableConnections.iterator();i.hasNext();) {
          ClientConnection c = i.next();
          if (c.getContext() == context) {
            contextConn = c;
            i.remove();
            break;
          }
        }
      }
      ClientConnection conn = contextConn;

      if (conn != null && !conn.isClosed()) {
        context.runOnContext(v -> handler.handle(conn));
      } else if (availableConnections.isEmpty() && connCount == maxSockets) {
        // Wait in queue
        if (maxWaitQueueSize < 0 || waiters.size() < maxWaitQueueSize) {
          waiters.add(new Waiter(handler, connectionExceptionHandler, context, canceled));
        } else {
          connectionExceptionHandler.handle(new ConnectionPoolTooBusyException("Connection pool reached max wait queue size of " + maxWaitQueueSize));
        }
      } else {
        if (availableConnections.size() > 0) {
          // We couldn't find a connection with the correct context in the pool, we should close a connection first
          availableConnections.poll().close();
        }
        // Create a new connection
        createNewConnection(handler, connectionExceptionHandler, context);
      }
    }

    // Called when the request has ended
    public synchronized void requestEnded(ClientConnection conn) {
      if (pipelining) {
        // Maybe the connection can be reused
        Waiter waiter = getNextWaiter(conn.getContext());
        if (waiter != null) {
          waiter.context.runOnContext(v -> waiter.handler.handle(conn));
        }
      }
    }

    // Called when the response has ended
    public synchronized void responseEnded(ClientConnection conn) {
      if (pipelining || keepAlive) {
        Waiter waiter = getNextWaiter(conn.getContext());
        if (waiter != null) {
          waiter.reuse(conn);
        } else if (!pipelining || conn.getOutstandingRequestCount() == 0) {
          // Return to set of available
          availableConnections.add(conn);
          waiter = getNextWaiter(null);
          if (waiter != null) {
            getConnection(waiter.handler, waiter.connectionExceptionHandler, waiter.context, waiter.canceled);
          }
        }
      } else {
        // Close it now
        conn.close();
      }
    }

    void closeAllConnections() {
      Set<ClientConnection> copy;
      synchronized (this) {
        copy = new HashSet<>(allConnections);
        allConnections.clear();
      }
      // Close outside sync block to avoid deadlock
      for (ClientConnection conn: copy) {
        try {
          conn.close();
        } catch (Throwable t) {
          log.error("Failed to close connection", t);
        }
      }
    }

    private void createNewConnection(Handler<ClientConnection> handler, Handler<Throwable> connectionExceptionHandler, ContextImpl context) {
      connCount++;
      connect(address.host, address.port, conn -> {
        synchronized (ConnectionManager.this) {
          allConnections.add(conn);
        }
        handler.handle(conn);
      }, connectionExceptionHandler, context, this);
    }

    private Waiter getNextWaiter(Context matchingCtx) {
      // See if there are any non-canceled waiters in the queue
      Waiter waiter = waiters.poll();
      while (waiter != null && waiter.canceled.getAsBoolean()) {
        waiter = waiters.poll();
      }
      // Check it has the same context than the provided matchingCtx when it is not null
      if (waiter != null && matchingCtx != null && matchingCtx != waiter.context) {
        waiters.addFirst(waiter);
        waiter = null;
      }
      return waiter;
    }

    // Called if the connection is actually closed, OR the connection attempt failed - in the latter case
    // conn will be null
    public synchronized void connectionClosed(ClientConnection conn) {
      connCount--;
      if (conn != null) {
        allConnections.remove(conn);
        availableConnections.remove(conn);
      }
      Waiter waiter = getNextWaiter(null);
      if (waiter != null) {
        // There's a waiter - so it can have a new connection
        createNewConnection(waiter.handler, waiter.connectionExceptionHandler, waiter.context);
      } else if (connCount == 0) {
        // No waiters and no connections - remove the ConnQueue
        connQueues.remove(address);
      }
    }
  }

  private static class TargetAddress {
    final String host;
    final int port;

    private TargetAddress(String host, int port) {
      this.host = host;
      this.port = port;
    }

    @Override
    public boolean equals(Object o) {
      if (this == o) return true;
      if (o == null || getClass() != o.getClass()) return false;
      TargetAddress that = (TargetAddress) o;
      if (port != that.port) return false;
      if (host != null ? !host.equals(that.host) : that.host != null) return false;
      return true;
    }

    @Override
    public int hashCode() {
      int result = host != null ? host.hashCode() : 0;
      result = 31 * result + port;
      return result;
    }
  }

  private static class Waiter {
    final Handler<ClientConnection> handler;
    final Handler<Throwable> connectionExceptionHandler;
    final ContextImpl context;
    final BooleanSupplier canceled;

    private Waiter(Handler<ClientConnection> handler, Handler<Throwable> connectionExceptionHandler, ContextImpl context,
                   BooleanSupplier canceled) {
      this.handler = handler;
      this.connectionExceptionHandler = connectionExceptionHandler;
      this.context = context;
      this.canceled = canceled;
    }

    void reuse(ClientConnection conn) {
      context.runOnContext(v -> handler.handle(conn));    }
  }


}
