package com.sun.net.httpserver;

import java.io.IOException;

/**
 * Android has no com.sun.net.httpserver: Cable Club brings the small part of it its local server uses (the
 * interface and the game talk to the application through it, on 127.0.0.1 only).
 */
public interface HttpHandler {
    void handle(HttpExchange exchange) throws IOException;
}
