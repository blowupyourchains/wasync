package org.atmosphere.tests;

import java.io.IOException;
import java.net.ServerSocket;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import org.atmosphere.cpr.AtmosphereHandler;
import org.atmosphere.cpr.AtmosphereResource;
import org.atmosphere.cpr.AtmosphereResourceEvent;
import org.atmosphere.nettosphere.Config;
import org.atmosphere.nettosphere.Nettosphere;
import org.atmosphere.wasync.ClientFactory;
import org.atmosphere.wasync.Event;
import org.atmosphere.wasync.Function;
import org.atmosphere.wasync.Request;
import org.atmosphere.wasync.RequestBuilder;
import org.atmosphere.wasync.Socket;
import org.atmosphere.wasync.impl.AtmosphereClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertNotNull;

/**
 *
 * @author Sebastian Lövdahl <slovdahl@hibox.fi>
 */
public class SSETest {

    private static final Logger logger = LoggerFactory.getLogger(SSETest.class);
    protected final static String RESUME = "Resume";
    protected Nettosphere server;
    protected String targetUrl;
    protected int port;

    protected int findFreePort() throws IOException {
        ServerSocket socket = null;

        try {
            socket = new ServerSocket(0);

            return socket.getLocalPort();
        } finally {
            if (socket != null) {
                socket.close();
            }
        }
    }

    @AfterMethod(alwaysRun = true)
    public void tearDownGlobal() throws Exception {
        if (server != null && server.isStarted()) {
            server.stop();
        }
    }

    @BeforeMethod(alwaysRun = true)
    public void start() throws IOException {
        port = findFreePort();
        targetUrl = "http://127.0.0.1:" + port;
    }

    Request.TRANSPORT transport() {
        return Request.TRANSPORT.SSE;
    }

    int statusCode() {
        return 200;
    }

    int notFoundCode() {
        return 404;
    }

    int getCount() {
        return 5;
    }

    @Test(timeOut = 20000)
    public void closeTest() throws Exception {
        Config config = new Config.Builder()
                .port(port)
                .host("127.0.0.1")
                .resource("/suspend", new AtmosphereHandler() {
            private final AtomicBoolean b = new AtomicBoolean(false);

            @Override
            public void onRequest(AtmosphereResource r) throws IOException {
                if (!b.getAndSet(true)) {
                    r.suspend(-1);
                }
                else {
                    r.getBroadcaster().broadcast(RESUME);
                }
            }

            @Override
            public void onStateChange(AtmosphereResourceEvent r) throws IOException {
                if (!r.isResuming() || !r.isCancelled()) {
                    r.getResource().getResponse().getWriter().print(r.getMessage());
                    r.getResource().resume();
                }
            }

            @Override
            public void destroy() {
            }
        }).build();

        server = new Nettosphere.Builder().config(config).build();
        assertNotNull(server);
        server.start();

        final CountDownLatch latch = new CountDownLatch(1);
        final AtomicReference<String> response = new AtomicReference<String>();
        AtmosphereClient client = ClientFactory.getDefault().newClient(AtmosphereClient.class);

        RequestBuilder request = client.newRequestBuilder()
                .method(Request.METHOD.GET)
                .uri(targetUrl + "/suspend")
                .transport(transport());

        Socket socket = client.create(client.newOptionsBuilder().reconnect(false).build());
        socket.on(Event.MESSAGE, new Function<String>() {
            @Override
            public void on(String t) {
                logger.info("Function invoked {}", t);
                response.set(t);
                latch.countDown();
            }
        }).on(new Function<Throwable>() {
            @Override
            public void on(Throwable t) {
                t.printStackTrace();
                latch.countDown();
            }
        }).open(request.build()).fire("PING");

        latch.await(10, TimeUnit.SECONDS);
        socket.close();
        server.stop();

        assertEquals(socket.status(), Socket.STATUS.CLOSE);
    }
}
