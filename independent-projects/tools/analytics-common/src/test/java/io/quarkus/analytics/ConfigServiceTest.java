package io.quarkus.analytics;

import static io.quarkus.analytics.ConfigService.ACCEPTANCE_PROMPT;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.io.IOException;
import java.nio.file.Files;
import java.time.Duration;
import java.util.Collections;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import io.quarkus.analytics.common.TestRestClient;
import io.quarkus.analytics.config.FileLocations;
import io.quarkus.analytics.config.TestFileLocationsImpl;
import io.quarkus.analytics.dto.config.NoopRemoteConfig;
import io.quarkus.analytics.dto.config.RemoteConfig;
import io.quarkus.analytics.util.FileUtils;
import io.quarkus.devtools.messagewriter.MessageWriter;

class ConfigServiceTest {

    private FileLocations fileLocations;

    @BeforeEach
    void setUp() throws IOException {
        fileLocations = new TestFileLocationsImpl();
    }

    @AfterEach
    void tearDown() throws IOException {
        ((TestFileLocationsImpl) fileLocations).deleteAll();
    }

    @Test
    void activeWithRemoteConfig() throws IOException {
        ConfigService configService = createConfigService();

        long lastModified = fileLocations.getRemoteConfigFile().toFile().lastModified();

        assertNotNull(configService);
        assertTrue(configService.isActive()); // if remote config not found, it will be downloaded (it shouldn't)
        assertEquals(lastModified, fileLocations.getRemoteConfigFile().toFile().lastModified(), "File must not change");
    }

    @Test
    void inactiveNoQuestionAsked() throws IOException {
        deleteLocalConfigFile();

        ConfigService configService = createConfigService();

        assertNotNull(configService);
        assertFalse(configService.isActive());
    }

    @Test
    void inactiveUserAnsweredNo() throws IOException {
        deleteLocalConfigFile();
        FileUtils.append("{\"disabled\":true}", fileLocations.getLocalConfigFile());

        ConfigService configService = createConfigService();

        assertNotNull(configService);
        assertFalse(configService.isActive());
    }

    @Test
    void activeConfig() throws IOException {
        RemoteConfig remoteConfig = RemoteConfig.builder()
                .active(true)
                .denyQuarkusVersions(Collections.emptyList())
                .denyUserIds(Collections.emptyList())
                .refreshInterval(Duration.ZERO).build();

        assertFalse(Files.exists(fileLocations.getRemoteConfigFile()));

        ConfigService configService = new ConfigService(new TestRestClient(remoteConfig),
                AnonymousUserId.getInstance(fileLocations, MessageWriter.info()),
                fileLocations,
                MessageWriter.info());

        assertNotNull(configService);
        assertTrue(configService.isActive());
        assertTrue(Files.exists(fileLocations.getRemoteConfigFile()));
    }

    @Test
    void remoteConfigOff() throws IOException {
        ConfigService configService = new ConfigService(new TestRestClient(NoopRemoteConfig.INSTANCE),
                AnonymousUserId.getInstance(fileLocations, MessageWriter.info()),
                fileLocations,
                MessageWriter.info());

        assertNotNull(configService);
        assertFalse(configService.isActive());
    }

    @Test
    void isArtifactActive() throws IOException {
        ConfigService configService = new ConfigService(new TestRestClient(NoopRemoteConfig.INSTANCE),
                AnonymousUserId.getInstance(fileLocations, MessageWriter.info()),
                fileLocations,
                MessageWriter.info());

        assertTrue(configService.isArtifactActive("allow.groupId",
                "allow.quarkus.version"));
        assertTrue(configService.isArtifactActive("allow.groupId",
                null));
        assertFalse(configService.isArtifactActive("",
                "allow.quarkus.version"));
        assertFalse(configService.isArtifactActive(null,
                null));
        assertFalse(configService.isArtifactActive("io.quarkus.opentelemetry",
                null));
    }

    @Test
    void isQuarkusVersionActive() throws IOException {
        AnonymousUserId userId = AnonymousUserId.getInstance(fileLocations, MessageWriter.info());
        RemoteConfig remoteConfig = RemoteConfig.builder()
                .active(true)
                .denyQuarkusVersions(List.of("deny.quarkus.version", "deny.quarkus.version2"))
                .denyUserIds(Collections.emptyList())
                .refreshInterval(Duration.ofHours(12)).build();
        ConfigService configService = new ConfigService(new TestRestClient(remoteConfig),
                AnonymousUserId.getInstance(fileLocations, MessageWriter.info()),
                fileLocations,
                MessageWriter.info());

        assertTrue(configService.isArtifactActive("allow.groupId",
                "allow.quarkus.version"));
        assertFalse(configService.isArtifactActive("allow.groupId",
                "deny.quarkus.version"));
        assertFalse(configService.isArtifactActive("allow.groupId",
                "deny.quarkus.version2"));
    }

    @Test
    void isUserIsDisabled() throws IOException {
        AnonymousUserId userId = AnonymousUserId.getInstance(fileLocations, MessageWriter.info());
        RemoteConfig remoteConfig = RemoteConfig.builder()
                .active(true)
                .denyQuarkusVersions(Collections.emptyList())
                .denyUserIds(List.of(userId.getUuid()))
                .refreshInterval(Duration.ofHours(12)).build();
        ConfigService configService = new ConfigService(new TestRestClient(remoteConfig),
                userId,
                fileLocations,
                MessageWriter.info());

        assertFalse(configService.isUserEnabled(remoteConfig, userId.getUuid()));
        assertFalse(configService.isActive());
    }

    @Test
    void userAcceptance_alreadyAnswered() throws IOException {
        ConfigService configService = createConfigService();
        configService.userAcceptance(s -> {
            assertTrue(Files.exists(fileLocations.getLocalConfigFile()), "Local config file must be present");
            fail("User already answered");
            return "y";
        });
        assertTrue(Files.exists(fileLocations.getLocalConfigFile()), "Local config file must be present");
    }

    @Test
    void userAcceptance_yes() throws IOException {
        deleteLocalConfigFile();

        ConfigService configService = createConfigService();

        configService.userAcceptance(s -> {
            assertEquals(ACCEPTANCE_PROMPT, s);
            return "y";
        });
        assertTrue(Files.exists(fileLocations.getLocalConfigFile()), "Local config file must be present");
        assertTrue(configService.isActive());
    }

    @Test
    void userAcceptance_no() throws IOException {
        deleteLocalConfigFile();

        ConfigService configService = createConfigService();

        configService.userAcceptance(s -> {
            assertEquals(ACCEPTANCE_PROMPT, s);
            return "n";
        });
        assertTrue(Files.exists(fileLocations.getLocalConfigFile()), "Local config file must be present");
        assertFalse(configService.isActive());
    }

    @Test
    void userAcceptance_fail() throws IOException {
        deleteLocalConfigFile();

        ConfigService configService = createConfigService();

        configService.userAcceptance(s -> {
            throw new RuntimeException("User input failed");
        });
        assertFalse(Files.exists(fileLocations.getLocalConfigFile()), "Local config file cannot be present");
        assertFalse(configService.isActive());
    }

    private void deleteLocalConfigFile() {
        fileLocations.getLocalConfigFile().toFile().delete();
    }

    private ConfigService createConfigService() throws IOException {
        RemoteConfig remoteConfig = RemoteConfig.builder()
                .active(true)
                .denyQuarkusVersions(Collections.emptyList())
                .denyUserIds(Collections.emptyList())
                .refreshInterval(Duration.ofHours(12)).build();

        FileUtils.write(remoteConfig, fileLocations.getRemoteConfigFile());

        ConfigService configService = new ConfigService(new TestRestClient(remoteConfig),
                AnonymousUserId.getInstance(fileLocations, MessageWriter.info()),
                fileLocations,
                MessageWriter.info());
        return configService;
    }
}
