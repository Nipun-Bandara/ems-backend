package com.ems.common.outbox;

import java.time.Duration;
import java.time.Instant;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Tuning for {@link OutboxPoller}. The defaults are meant to be left alone; they exist as
 * properties so an environment with a slower broker can widen the backoff without a code
 * change.
 *
 * <p>The poll interval itself is read straight from {@code ems.outbox.poll-interval} by the
 * {@code @Scheduled} annotation, which cannot take its value from a bean.
 */
@ConfigurationProperties("ems.outbox")
public class OutboxProperties {

    /** Rows claimed per poll. */
    private int batchSize = 100;

    /** Publish attempts before a row is left for a human to look at. */
    private int maxAttempts = 10;

    /** Wait after the first failed attempt. */
    private Duration backoffInitial = Duration.ofSeconds(2);

    /** Factor the wait grows by with each further failure. */
    private double backoffMultiplier = 2.0;

    /** Ceiling on the wait, so a long outage does not push a row hours into the future. */
    private Duration backoffMax = Duration.ofMinutes(5);

    /**
     * When a row that has just failed for the {@code attempts}th time may be tried again.
     */
    Instant nextAttemptAfter(int attempts, Instant from) {
        double millis = backoffInitial.toMillis() * Math.pow(backoffMultiplier, Math.max(0, attempts - 1));
        return from.plusMillis((long) Math.min(millis, backoffMax.toMillis()));
    }

    public int getBatchSize() {
        return batchSize;
    }

    public void setBatchSize(int batchSize) {
        this.batchSize = batchSize;
    }

    public int getMaxAttempts() {
        return maxAttempts;
    }

    public void setMaxAttempts(int maxAttempts) {
        this.maxAttempts = maxAttempts;
    }

    public Duration getBackoffInitial() {
        return backoffInitial;
    }

    public void setBackoffInitial(Duration backoffInitial) {
        this.backoffInitial = backoffInitial;
    }

    public double getBackoffMultiplier() {
        return backoffMultiplier;
    }

    public void setBackoffMultiplier(double backoffMultiplier) {
        this.backoffMultiplier = backoffMultiplier;
    }

    public Duration getBackoffMax() {
        return backoffMax;
    }

    public void setBackoffMax(Duration backoffMax) {
        this.backoffMax = backoffMax;
    }
}
