package ru.pulsedoma.issues;

public interface AttachmentStore {
    void put(String key, byte[] data, String mime);
    byte[] get(String key);
}
