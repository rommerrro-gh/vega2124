package ru.pulsedoma.common;

public interface WebhookQueue {
    void enqueue(String payload);
}
