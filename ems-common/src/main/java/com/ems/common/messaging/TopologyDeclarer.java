package com.ems.common.messaging;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.AmqpException;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ApplicationListener;

/**
 * Opens the broker connection once the application is up, so the exchanges and queues in
 * the context are actually declared.
 *
 * <p>{@code RabbitAdmin} declares on connection creation, and the connection is lazy. A
 * service that consumes nothing — or that only publishes much later — would otherwise leave
 * its topology undeclared until its first message, which turns a wiring mistake into a
 * runtime surprise and leaves a freshly started stack looking empty in the management UI.
 *
 * <p>A broker that is down at startup is not fatal. The service still comes up and the
 * declarations happen on the first connection that does succeed.
 */
class TopologyDeclarer implements ApplicationListener<ApplicationReadyEvent> {

    private static final Logger log = LoggerFactory.getLogger(TopologyDeclarer.class);

    private final ConnectionFactory connectionFactory;

    TopologyDeclarer(ConnectionFactory connectionFactory) {
        this.connectionFactory = connectionFactory;
    }

    @Override
    public void onApplicationEvent(ApplicationReadyEvent event) {
        try {
            // Not closed on purpose: this is the shared cached connection the rest of the
            // service goes on to use.
            connectionFactory.createConnection();
            log.info("Declared the EMS topology on the broker");
        } catch (AmqpException ex) {
            log.warn(
                    "Could not reach the broker at startup, so the EMS topology is not declared yet; "
                            + "it will be declared on the first connection that succeeds",
                    ex);
        }
    }
}
