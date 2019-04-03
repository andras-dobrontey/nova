/*
 * Copyright (c) Squaredesk GmbH and Oliver Dotzauer.
 *
 * This program is distributed under the squaredesk open source license. See the LICENSE file
 * distributed with this work for additional information regarding copyright ownership. You may also
 * obtain a copy of the license at
 *
 *   https://squaredesk.ch/license/oss/LICENSE
 */
package ch.squaredesk.nova.comm.websockets;

import ch.squaredesk.nova.comm.retrieving.IncomingMessage;
import ch.squaredesk.nova.comm.websockets.client.ClientEndpoint;
import ch.squaredesk.nova.comm.websockets.server.ServerEndpoint;
import ch.squaredesk.nova.metrics.Metrics;
import com.ning.http.client.AsyncHttpClient;
import com.ning.http.client.AsyncHttpClientConfig;
import io.dropwizard.metrics5.Counter;
import io.dropwizard.metrics5.Meter;
import io.reactivex.Flowable;
import io.reactivex.subscribers.TestSubscriber;
import org.awaitility.Awaitility;
import org.glassfish.grizzly.http.server.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.hamcrest.Matchers.*;
import static org.hamcrest.junit.MatcherAssert.assertThat;
import static org.junit.jupiter.api.Assertions.*;

@Tag("medium")
class WebsocketAdapterTest {
    HttpServer httpServer = HttpServer.createSimpleServer("/", 7777);
    AsyncHttpClientConfig cf = new AsyncHttpClientConfig.Builder().build();
    AsyncHttpClient httpClient = new AsyncHttpClient(cf);

    Metrics metrics;

    WebSocketAdapter sut;

    @BeforeEach
    void setup() throws Exception {
        metrics = new Metrics();

        sut = WebSocketAdapter.builder()
                .setHttpServer(httpServer)
                .setHttpClient(httpClient)
                .setMetrics(metrics)
                .build();

        httpServer.start();
    }

    @AfterEach
    void tearDown() {
        httpServer.shutdownNow();
        httpClient.close();
    }

    @Test
    void serverFunctionsCannotBeInvokedIfNotProperlySetup() {
        sut = WebSocketAdapter.builder()
                .setMetrics(new Metrics())
                .build();

        IllegalStateException ex = assertThrows(
                IllegalStateException.class,
                () -> sut.acceptConnections("someDest"));
        assertThat(ex.getMessage(), is("Adapter not initialized properly for server mode"));
    }

    @Test
    void clientFunctionsCannotBeInvokedIfNotProperlySetup() {
        sut = WebSocketAdapter.builder()
                .setMetrics(new Metrics())
                .build();

        IllegalStateException ex = assertThrows(
                IllegalStateException.class,
                () -> sut.connectTo("someDest"));
        assertThat(ex.getMessage(), is("Adapter not initialized properly for client mode"));
    }

    @Test
    void sendAndReceiveAfterInitiatingConnection() throws Exception {
        String destinationUri = "ws://echo.websocket.org/";
        CountDownLatch connectionLatch = new CountDownLatch(1);
        CountDownLatch closeLatch = new CountDownLatch(1);

        Counter totalSubscriptions = metrics.getCounter("websocket", "subscriptions", "total");
        Counter specificSubscriptions = metrics.getCounter("websocket", "subscriptions", destinationUri);
        assertThat(totalSubscriptions.getCount(), is(0L));
        assertThat(specificSubscriptions.getCount(), is(0L));

        ClientEndpoint endpoint = sut.connectTo(destinationUri);
        endpoint.connectedWebSockets().subscribe( socket -> connectionLatch.countDown() );
        endpoint.closedWebSockets().subscribe( socket -> closeLatch.countDown() );
        connectionLatch.await(2, TimeUnit.SECONDS);
        assertThat(connectionLatch.getCount(), is(0L));
        assertThat(totalSubscriptions.getCount(), is(1l));
        assertThat(specificSubscriptions.getCount(), is(1l));

        testEchoFromClientPerspective(destinationUri, endpoint);

        endpoint.close();

        closeLatch.await(10, TimeUnit.SECONDS);
        assertThat(closeLatch.getCount(), is(0L));
        assertThat(totalSubscriptions.getCount(), is(0L));
        assertThat(specificSubscriptions.getCount(), is(0L));
    }

    @Test
    void sendAndReceiveAfterAcceptingConnection() throws Exception {
        String serverDestination = "echo";
        String clientDestination = "ws://127.0.0.1:7777/" + serverDestination;

        CountDownLatch connectionLatch = new CountDownLatch(1);
        CountDownLatch closeLatch = new CountDownLatch(1);

        Counter totalSubscriptions = metrics.getCounter("websocket", "subscriptions", "total");
        Counter specificSubscriptions = metrics.getCounter("websocket", "subscriptions", serverDestination);
        assertThat(totalSubscriptions.getCount(), is(0L));
        assertThat(specificSubscriptions.getCount(), is(0L));

        ServerEndpoint endpointAccepting = null;
        ClientEndpoint endpointInitiating = null;
        try {
            endpointAccepting = sut.acceptConnections(serverDestination);
            endpointAccepting.connectedWebSockets().subscribe(socket -> connectionLatch.countDown());
            endpointAccepting.closedWebSockets().subscribe(socket -> closeLatch.countDown());
            endpointAccepting.messages(s -> s).subscribe(
                    incomingMessage -> incomingMessage.metaData.details.webSocket.send(incomingMessage.message));
            endpointInitiating = sut.connectTo(clientDestination);

            connectionLatch.await(2, TimeUnit.SECONDS);
            assertThat(connectionLatch.getCount(), is(0L));
            assertThat(totalSubscriptions.getCount(), is(1l));
            assertThat(specificSubscriptions.getCount(), is(1l));
            testEchoFromClientPerspective(clientDestination, endpointInitiating);
        } finally {
            if (endpointInitiating != null) {
                endpointInitiating.close();
                closeLatch.await(10, TimeUnit.SECONDS);
                assertThat(closeLatch.getCount(), is(0L));
                assertThat(totalSubscriptions.getCount(), is(0L));
                assertThat(specificSubscriptions.getCount(), is(0L));
            }

            if (endpointAccepting != null) endpointAccepting.close();
        }

    }

    @Test
    void receiveUnparsableMessagesServerSide() throws Exception {
        String serverDestination = "echoBroken";
        String clientDestination = "ws://127.0.0.1:7777/" + serverDestination;


        ServerEndpoint serverEndpoint = null;
        ClientEndpoint clientEndpoint = null;
        try {
            serverEndpoint = sut.acceptConnections(serverDestination);
            serverEndpoint.messages(Integer.class).subscribe(
                    incomingMessage -> {
                        incomingMessage.metaData.details.webSocket.send(incomingMessage.message);
                    });

            Meter totalMessagesReceived = metrics.getMeter("websocket", "received", "total");
            Meter totalUnparsable = metrics.getMeter("websocket", "received", "unparsable", "total");
            Meter specificUnparsable = metrics.getMeter("websocket", "received", "unparsable", serverDestination);
            assertThat(totalUnparsable.getCount(), is(0L));
            assertThat(specificUnparsable.getCount(), is(0L));

            WebSocket[] sendSocketHolder = new WebSocket[1];
            CountDownLatch sendSocketLatch = new CountDownLatch(1);
            clientEndpoint = sut.connectTo(clientDestination);
            clientEndpoint.connectedWebSockets().subscribe(socket -> {
                sendSocketHolder[0] = socket;
                sendSocketLatch.countDown();
            });
            sendSocketLatch.await(2, TimeUnit.SECONDS);
            assertNotNull(sendSocketHolder[0]);

            clientEndpoint.send("One");
            clientEndpoint.send("Two");
            clientEndpoint.send("33");

            Awaitility.await().atMost(10, TimeUnit.SECONDS).until(totalMessagesReceived::getCount, is(1L));
            assertThat(totalUnparsable.getCount(), is(2l));
            assertThat(specificUnparsable.getCount(), is(2l));
        } finally {
            if (clientEndpoint != null) {
                clientEndpoint.close();
            }
            if (serverEndpoint != null) serverEndpoint.close();
        }

    }

    @Test
    void receiveUnparsableMessagesClientSide() throws Exception {
        String serverDestination = "echoBroken";
        String clientDestination = "ws://127.0.0.1:7777/" + serverDestination;


        ServerEndpoint serverEndpoint = null;
        ClientEndpoint clientEndpoint = null;
        try {
            serverEndpoint = sut.acceptConnections(serverDestination);
            CountDownLatch sendSocketLatch = new CountDownLatch(1);
            WebSocket[] sendSocketHolder = new WebSocket[1];
            serverEndpoint.connectedWebSockets().subscribe(socket -> {
                sendSocketHolder[0] = socket;
                sendSocketLatch.countDown();
            });

            clientEndpoint = sut.connectTo(clientDestination);
            sendSocketLatch.await(2, TimeUnit.SECONDS);
            assertNotNull(sendSocketHolder[0]);
            testEchoWithUnparsableMessages(clientDestination, clientEndpoint, sendSocketHolder[0]);
        } finally {
            if (clientEndpoint != null) {
                clientEndpoint.close();
            }
            if (serverEndpoint != null) serverEndpoint.close();
            httpServer.shutdownNow();
        }

    }

    void testEchoFromClientPerspective(String destination, ClientEndpoint endpoint) throws Exception {
        Meter totalSent = metrics.getMeter("websocket", "sent", "total");
        Meter specificSent = metrics.getMeter("websocket", "sent", destination);
        Meter totalReceived = metrics.getMeter("websocket", "received", "total");
        Meter specificReceived = metrics.getMeter("websocket", "received", destination);
        assertThat(totalReceived.getCount(), is(0L));
        assertThat(specificReceived.getCount(), is(0L));
        assertThat(totalSent.getCount(), is(0L));
        assertThat(specificSent.getCount(), is(0L));

        Flowable<IncomingMessage<Integer, IncomingMessageMetaData>> messages = endpoint.messages(Integer::parseInt);
        TestSubscriber<IncomingMessage<Integer, IncomingMessageMetaData>> testSubscriber = messages.test();

        endpoint.send(1);
        endpoint.send(2);
        endpoint.send(33);
        assertThat(totalSent.getCount(), greaterThanOrEqualTo(3l));
        assertThat(specificSent.getCount(), is(3l));

        long maxWaitTime = System.currentTimeMillis() + TimeUnit.SECONDS.toMillis(20);
        while (testSubscriber.valueCount() < 3 && System.currentTimeMillis() < maxWaitTime) {
            TimeUnit.MILLISECONDS.sleep(500);
        }
        testSubscriber.assertValueCount(3);
        assertThat(testSubscriber.values().get(0).message, is(1));
        assertThat(testSubscriber.values().get(1).message, is(2));
        assertThat(testSubscriber.values().get(2).message, is(33));
        for (int i = 0; i < 3; i++) {
            assertNotNull(testSubscriber.values().get(i).metaData);
            assertThat(testSubscriber.values().get(i).metaData.destination, is(destination));
            assertNotNull(testSubscriber.values().get(i).metaData);
            assertNotNull(testSubscriber.values().get(i).metaData.details.webSocket);
        }
        assertThat(specificReceived.getCount(), is(3l));
        assertThat(totalReceived.getCount(), greaterThanOrEqualTo(3l));
        testSubscriber.dispose();
    }

    void testEchoWithUnparsableMessages(String destination, Endpoint receivingEndpoint, WebSocket sendSocket) throws Exception {
        Meter totalUnparsable = metrics.getMeter("websocket", "received", "unparsable", "total");
        Meter specificUnparsable = metrics.getMeter("websocket", "received", "unparsable", destination);
        assertThat(totalUnparsable.getCount(), is(0L));
        assertThat(specificUnparsable.getCount(), is(0L));

        Flowable<IncomingMessage<Integer, IncomingMessageMetaData>> messages = receivingEndpoint.messages(Integer.class);
        TestSubscriber<IncomingMessage<Integer, IncomingMessageMetaData>> testSubscriber = messages.test();

        sendSocket.send("One");
        sendSocket.send("Two");
        sendSocket.send("33");

        Awaitility.await().atMost(20, TimeUnit.SECONDS).until(testSubscriber::valueCount, is(1));
        testSubscriber.assertValueCount(1);
        assertThat(testSubscriber.values().get(0).message, is(33));
        assertThat(totalUnparsable.getCount(), is(2l));
        assertThat(specificUnparsable.getCount(), is(2l));
        testSubscriber.dispose();
    }

    @Test
    void broadcastWorks() throws Exception {
        String serverDestination = "echo";
        String clientDestination = "ws://127.0.0.1:7777/" + serverDestination;

        CountDownLatch connectionLatch = new CountDownLatch(3);
        List<WebSocket> webSockets = new ArrayList<>();
        CountDownLatch messageLatch1 = new CountDownLatch(1);
        CountDownLatch messageLatch2 = new CountDownLatch(2);
        CountDownLatch messageLatch3 = new CountDownLatch(3);

        ServerEndpoint serverEndpoint = null;
        try {
            serverEndpoint = sut.acceptConnections(serverDestination);
            serverEndpoint.connectedWebSockets().subscribe(socket -> {
                webSockets.add(socket);
                connectionLatch.countDown();
            });

            TestSubscriber<IncomingMessage<Integer, IncomingMessageMetaData>> testSubscriber1 =
                    sut.connectTo(clientDestination).messages(Integer::parseInt).test();
            TestSubscriber<IncomingMessage<Integer, IncomingMessageMetaData>> testSubscriber2 =
                    sut.connectTo(clientDestination).messages(Integer::parseInt).test();
            sut.connectTo(clientDestination).messages(Integer::parseInt).subscribe(message -> {
                messageLatch1.countDown();
                messageLatch2.countDown();
                messageLatch3.countDown();
            });

            connectionLatch.await(2, TimeUnit.SECONDS);
            assertThat(connectionLatch.getCount(), is(0L));

            serverEndpoint.broadcast(1);
            messageLatch1.await(2, TimeUnit.SECONDS); assertThat(messageLatch1.getCount(), is(0L));
            webSockets.remove(0).close();

            serverEndpoint.broadcast(2);
            messageLatch2.await(2, TimeUnit.SECONDS); assertThat(messageLatch2.getCount(), is(0L));
            webSockets.remove(0).close();

            serverEndpoint.broadcast(3);
            messageLatch3.await(2, TimeUnit.SECONDS); assertThat(messageLatch3.getCount(), is(0L));

            testSubscriber1.assertValueCount(1);
            assertThat(testSubscriber1.values().get(0).message, is(1));
            testSubscriber2.assertValueCount(2);
            assertThat(testSubscriber2.values().get(0).message, is(1));
            assertThat(testSubscriber2.values().get(1).message, is(2));

            webSockets.remove(0).close();
        } finally {
            if (serverEndpoint != null) serverEndpoint.close();
            httpServer.shutdownNow();
        }

    }

    @Test
    void specificCloseReasonMustNotBeUsedByServerEndpoint() throws Exception {
        String serverDestination = "forbiddenServerCloseReason";

        ServerEndpoint serverEndpoint = sut.acceptConnections(serverDestination);
        assertThrows(IllegalArgumentException.class,
                () -> serverEndpoint.close(CloseReason.CLOSED_ABNORMALLY));
        assertThrows(IllegalArgumentException.class,
                () -> serverEndpoint.close(CloseReason.NO_STATUS_CODE));
    }

    @Test
    void specificCloseReasonMustNotBeUsedByClientEndpoint() throws Exception {
        String serverDestination = "forbiddenClientCloseReason";
        String clientDestination = "ws://127.0.0.1:7777/" + serverDestination;

        sut.acceptConnections(serverDestination);
        ClientEndpoint clientEndpoint = sut.connectTo(clientDestination);
        assertThrows(IllegalArgumentException.class,
                () -> clientEndpoint.close(CloseReason.CLOSED_ABNORMALLY));
        assertThrows(IllegalArgumentException.class,
                () -> clientEndpoint.close(CloseReason.NO_STATUS_CODE));
    }

    @Test
    void serverSideCloseReasonTransportedToClients() throws Exception {
        CountDownLatch closeLatch = new CountDownLatch(2);
        List<CloseReason> closeReasons = new ArrayList<>();

        String serverDestination = "clientCloseReason";
        String clientDestination = "ws://127.0.0.1:7777/" + serverDestination;

        ServerEndpoint serverEndpoint = sut.acceptConnections(serverDestination);

        ClientEndpoint clientEndpoint1 = sut.connectTo(clientDestination);
        clientEndpoint1.closedWebSockets().subscribe(pair -> {
            closeReasons.add(pair._2);
            closeLatch.countDown();
        });
        ClientEndpoint clientEndpoint2 = sut.connectTo(clientDestination);
        clientEndpoint2.closedWebSockets().subscribe(pair -> {
            closeReasons.add(pair._2);
            closeLatch.countDown();
        });
        serverEndpoint.close(CloseReason.GOING_AWAY);

        closeLatch.await(2, TimeUnit.SECONDS);
        assertThat(closeLatch.getCount(), is(0L));

        assertThat(closeReasons, contains(CloseReason.GOING_AWAY, CloseReason.GOING_AWAY));
    }


    /**
     * The grizzly implementation we are using for the server side ALWAYS calls onClose()
     * with CloseReason.GOING_AWAY, no matter what the client sends :-(.
     */
    @Test
    void clientSideCloseReasonNotTransportedToServer() throws Exception {
        CountDownLatch closeLatch = new CountDownLatch(2);
        List<CloseReason> closeReasons = new ArrayList<>();

        String serverDestination = "clientCloseReason";
        String clientDestination = "ws://127.0.0.1:7777/" + serverDestination;

        ServerEndpoint serverEndpoint = sut.acceptConnections(serverDestination);
        serverEndpoint.closedWebSockets().subscribe(pair -> {
            closeReasons.add(pair._2);
            closeLatch.countDown();
        });

        ClientEndpoint clientEndpoint1 = sut.connectTo(clientDestination);
        clientEndpoint1.close(CloseReason.TLS_HANDSHAKE_FAILURE);
        ClientEndpoint clientEndpoint2 = sut.connectTo(clientDestination);
        clientEndpoint2.close(CloseReason.SERVICE_RESTART);

        closeLatch.await(2, TimeUnit.SECONDS);
        assertThat(closeLatch.getCount(), is (0L));

        assertThat(closeReasons, contains(CloseReason.GOING_AWAY, CloseReason.GOING_AWAY));
    }

    @Test
    void informationCanBeAppendedToWebSocket() throws Exception {
        Metrics metrics = new Metrics();
        WebSocketAdapter sut = WebSocketAdapter.builder()
                .setHttpServer(httpServer)
                .setHttpClient(httpClient)
                .setMetrics(metrics)
                .build();
        httpServer.start();

        Random random = new Random();
        AtomicInteger clientId = new AtomicInteger();
        CountDownLatch connectionLatch = new CountDownLatch(2);
        CountDownLatch idLatch = new CountDownLatch(2);
        CountDownLatch latch = new CountDownLatch(4);

        String serverDestination = "infoAttachment";
        String clientDestination = "ws://127.0.0.1:7777/" + serverDestination;

        // create echo endpoint
        ServerEndpoint serverEndpoint = sut.acceptConnections(serverDestination);
        // echo all incoming message and append the clientID
        serverEndpoint.messages(s -> s).subscribe(incomingMessage -> {
            WebSocket webSocket = incomingMessage.metaData.details.webSocket;
            if (incomingMessage.message.startsWith("ID=")) {
                String id = incomingMessage.message.substring("ID=".length());
                webSocket.setUserProperty("clientId", id);
                webSocket.send("ACK " + id);
            } else {
                String id = webSocket.getUserProperty("clientId");
                webSocket.send(id + " - " + incomingMessage.message);
            }
        });


        // connect multiple clients to server
        ClientEndpoint[] clientEndpoints = new ClientEndpoint[] {
                sut.connectTo(clientDestination),
                sut.connectTo(clientDestination)
        };
        Arrays.stream(clientEndpoints).forEach(endpoint -> {
            endpoint.messages(s -> s).subscribe(incomingMessage -> {
                if (incomingMessage.message.startsWith("ACK ")) {
                    String myId = endpoint.getUserProperty("myId");
                    String receivedId = incomingMessage.message.substring("ACK ".length());
                    assertThat(receivedId, is(myId));
                    idLatch.countDown();
                } else {
                    String myId = endpoint.getUserProperty("myId");
                    String receivedId = incomingMessage.message.substring(0, incomingMessage.message.indexOf(" - "));
                    assertThat(receivedId, is(myId));
                    // assert message starts with my ID
                    latch.countDown();
                }
            });
        });

        // wait till connected
        Arrays.stream(clientEndpoints).forEach(endpoint -> {
            endpoint.connectedWebSockets().subscribe(incomingMessage -> {
                connectionLatch.countDown();
            });
        });
        connectionLatch.await(5, TimeUnit.SECONDS);
        assertThat(connectionLatch.getCount(), is (0L));

        // send "login"
        Arrays.stream(clientEndpoints).forEach(endpoint -> {
            String myId = String.valueOf(clientId.incrementAndGet());
            endpoint.setUserProperty("myId", myId);
            try {
                endpoint.send("ID=" + myId);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });
        idLatch.await(5, TimeUnit.SECONDS);
        assertThat(idLatch.getCount(), is (0L));

        // and two more messages
        Arrays.stream(clientEndpoints).forEach(endpoint -> {
            try {
                endpoint.send("xxx" + random.nextInt());
                endpoint.send("xxx" + random.nextInt());
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });

        latch.await(5, TimeUnit.SECONDS);
        assertThat(latch.getCount(), is (0L));

        // verify that we can clear a single user property
        assertNotNull(clientEndpoints[0].getUserProperty("myId"));
        clientEndpoints[0].setUserProperty("myId", null);
        assertNull(clientEndpoints[0].getUserProperty("myId"));

        // verify that we can clear all user properties
        clientEndpoints[1].setUserProperty("myId2", "null");
        assertNotNull(clientEndpoints[1].getUserProperty("myId"));
        assertNotNull(clientEndpoints[1].getUserProperty("myId2"));
        clientEndpoints[1].clearUserProperties();
        assertNull(clientEndpoints[1].getUserProperty("myId"));
        assertNull(clientEndpoints[1].getUserProperty("myId2"));

    }
}