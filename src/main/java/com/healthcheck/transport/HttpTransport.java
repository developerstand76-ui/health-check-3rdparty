package com.healthcheck.transport;

import com.healthcheck.model.Target;

public interface HttpTransport {
    HttpResponseData execute(Target target) throws Exception;
}
