/*
 * Copyright 2017 Huawei Technologies Co., Ltd
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.servicecomb.saga.transports.httpclient;

import io.servicecomb.saga.core.SagaResponse;
import io.servicecomb.saga.core.SuccessfulSagaResponse;
import io.servicecomb.saga.core.TransactionFailedException;
import io.servicecomb.saga.core.Transport;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.HashMap;
import java.util.Map;
import java.util.Map.Entry;
import java.util.function.Function;
import kamon.annotation.EnableKamon;
import kamon.annotation.Segment;
import org.apache.http.HttpResponse;
import org.apache.http.client.fluent.Form;
import org.apache.http.client.fluent.Request;
import org.apache.http.client.utils.URIBuilder;
import org.apache.http.entity.ContentType;
import org.apache.logging.log4j.core.util.IOUtils;

@EnableKamon
public class HttpClientTransport implements Transport {


  private final Map<String, Function<URI, Request>> requestFactories = new HashMap<String, Function<URI, Request>>() {{
    put("GET", Request::Get);
    put("POST", Request::Post);
    put("PUT", Request::Put);
    put("DELETE", Request::Delete);
  }};

  @Segment(name = "transport", category = "network", library = "kamon")
  @Override
  public SagaResponse with(String address, String path, String method, Map<String, Map<String, String>> params) {
    URIBuilder builder = new URIBuilder().setScheme("http").setHost(address).setPath(path);

    if (params.containsKey("query")) {
      for (Entry<String, String> entry : params.get("query").entrySet()) {
        builder.addParameter(entry.getKey(), entry.getValue());
      }
    }

    try {
      URI uri = builder.build();
      Request request = requestFactories.getOrDefault(
          method.toUpperCase(),
          exceptionThrowingFunction(method)).apply(uri);

      if (params.containsKey("json")) {
        request.bodyString(params.get("json").get("body"), ContentType.APPLICATION_JSON);
      }

      if (params.containsKey("form")) {
        Form form = Form.form();
        for (Entry<String, String> entry : params.get("form").entrySet()) {
          form.add(entry.getKey(), entry.getValue()).build();
        }
        request.bodyForm(form.build());
      }

      return this.on(request);
    } catch (URISyntaxException e) {
      throw new TransactionFailedException("Wrong request URI", e);
    }
  }

  private Function<URI, Request> exceptionThrowingFunction(String method) {
    return u -> {
      throw new TransactionFailedException("No such method " + method);
    };
  }

  private SagaResponse on(Request request) {
    try {
      HttpResponse httpResponse = request.execute().returnResponse();
      int statusCode = httpResponse.getStatusLine().getStatusCode();
      String content = IOUtils.toString(new InputStreamReader(httpResponse.getEntity().getContent()));
      if (statusCode >= 200 && statusCode < 300) {
        return new SuccessfulSagaResponse(statusCode, content);
      }
      throw new TransactionFailedException("The remote service returned with status code " + statusCode
          + ", reason " + httpResponse.getStatusLine().getReasonPhrase()
          + ", and content " + content);
    } catch (IOException e) {
      throw new TransactionFailedException("Network Error", e);
    }
  }
}
