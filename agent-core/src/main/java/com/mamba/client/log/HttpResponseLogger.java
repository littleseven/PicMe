package com.mamba.client.log;

import static com.mamba.client.log.HttpRequestLogger.format;

import com.mamba.Internal;
import com.mamba.client.SuccessfulHttpResponse;
import org.slf4j.Logger;

@Internal
class HttpResponseLogger {

    static void log(Logger log, SuccessfulHttpResponse response) {
        try {
            log.info(
                    "HTTP response: statusCode={}, headers={}, body={}",
                    response.statusCode(),
                    format(response.headers()),
                    HttpRequestLogger.compact(response.body()));
        } catch (Exception e) {
            log.warn("Exception occurred while logging HTTP response: {}", e.getMessage());
        }
    }
}
