package ru.pulsedoma.bootstrap;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import ru.pulsedoma.issues.AttachmentStore;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

@Component
@Profile("demo")
public class LocalAttachmentStore implements AttachmentStore {
    private final Path root;

    public LocalAttachmentStore(@Value("${storage.demo.path:./data/demo-attachments}") String path) {
        root = Path.of(path).toAbsolutePath().normalize();
    }

    @Override
    public void put(String key, byte[] data, String mime) {
        try {
            Path target = safe(key);
            Files.createDirectories(target.getParent());
            Files.write(target, data);
        } catch (IOException e) {
            throw new IllegalStateException("Could not save demo attachment", e);
        }
    }

    @Override
    public byte[] get(String key) {
        try {
            return Files.readAllBytes(safe(key));
        } catch (IOException e) {
            throw new IllegalStateException("Could not read demo attachment", e);
        }
    }

    private Path safe(String key) {
        Path target = root.resolve(key).normalize();
        if (!target.startsWith(root)) throw new IllegalArgumentException("Invalid storage key");
        return target;
    }
}
