/*
 * Copyright (c) 2011-2013 The original author or authors
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
package io.vertx.core.http;

import io.vertx.codegen.annotations.CacheReturn;
import io.vertx.codegen.annotations.VertxGen;
import io.vertx.core.MultiMap;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.json.JsonObject;

/**
 * An HTTP response.
 * <p>
 * The usual HTTP response attributes are available:
 * <ul>
 *   <li>{@link #statusCode()} the HTTP status code</li>
 *   <li>{@link #statusMessage()} the HTTP status message</li>
 *   <li>{@link #headers()} the HTTP headers</li>
 *   <li>{@link #version()} the HTTP version</li>
 * </ul>
 * <p>
 * The body of the response is returned by {@link #body()} decoded as the format specified by the {@link HttpClientResponseBuilder} that
 * built the response.
 * <p>
 * Keep in mind that using this {@code HttpResponse} impose to fully buffer the response body and should be used for payload
 * that can fit in memory.
 *
 * @author <a href="mailto:julien@julienviet.com">Julien Viet</a>
 */
@VertxGen
public interface HttpResponse<T> {

  /**
   * @return the version of the response
   */
  @CacheReturn
  HttpVersion version();

  /**
   * @return the status code of the response
   */
  @CacheReturn
  int statusCode();

  /**
   * @return the status message of the response
   */
  @CacheReturn
  String statusMessage();

  /**
   * @return the headers
   */
  @CacheReturn
  MultiMap headers();

  /**
   * @return the response body in the format it was decoded.
   */
  @CacheReturn
  T body();

  /**
   * @return the response body decoded as a {@link Buffer}
   */
  Buffer bodyAsBuffer();

  /**
   * @return the response body decoded as a {@code String}
   */
  String bodyAsString();

  /**
   * @return the response body decoded as a {@code String} given a specific {@code encoding}
   */
  String bodyAsString(String encoding);

  /**
   * @return the response body decoded as a json object
   */
  JsonObject bodyAsJsonObject();

  /**
   * @return the response body decoded as the specified {@code type} with the Jackson mapper.
   */
  <R> R bodyAs(Class<R> type);

}
