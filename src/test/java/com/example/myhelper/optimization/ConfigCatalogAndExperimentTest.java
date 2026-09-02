package com.example.myhelper.optimization;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.util.ReflectionTestUtils;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 验证配置目录覆盖所有叶子，且实验始终在副本中发生。 */
class ConfigCatalogAndExperimentTest {

    @TempDir
    Path temporaryDirectory;

    @Test
    void discoversEveryLeafClassifiesRiskAndLeavesSourceUntouched() throws Exception {
        Path source = temporaryDirectory.resolve("sample.yml");
        Files.writeString(source, """
                myhelper:
                  executor:
                    max-retries: 3
                    label: stable
                  remote:
                    url: http://127.0.0.1:6333
                  auth:
                    api-key: do-not-display
                  feature:
                    enabled: true
                """);

        ConfigCatalogService catalog = catalogAt(temporaryDirectory);

        assertEquals(ConfigCatalogService.Risk.TUNABLE,
                catalog.find("myhelper.executor.max-retries").orElseThrow().risk());
        assertEquals(ConfigCatalogService.Risk.OBSERVE_ONLY,
                catalog.find("myhelper.executor.label").orElseThrow().risk());
        assertEquals(ConfigCatalogService.Risk.RESTART_OR_EXTERNAL,
                catalog.find("myhelper.remote.url").orElseThrow().risk());
        ConfigCatalogService.ConfigParameter secret = catalog.find("myhelper.auth.api-key").orElseThrow();
        assertEquals(ConfigCatalogService.Risk.PROTECTED_SECRET, secret.risk());
        assertEquals("<redacted>", secret.displayValue());
        assertEquals(ConfigCatalogService.Risk.REQUIRES_APPROVAL,
                catalog.find("myhelper.feature.enabled").orElseThrow().risk());

        ConfigExperimentService experiments = new ConfigExperimentService(catalog);
        Path experimentDirectory = temporaryDirectory.resolve("experiments");
        ReflectionTestUtils.setField(experiments, "experimentDir", experimentDirectory.toString());
        experiments.ensureDirectory();

        ConfigExperimentService.Experiment experiment = experiments.stage(
                "myhelper.executor.max-retries", 4, "verify copy-only experiment");

        assertTrue(Files.exists(experiment.file()));
        assertTrue(Files.readString(source).contains("max-retries: 3"));
        assertEquals(4L, numericValue(experiment.file(), "myhelper", "executor", "max-retries").longValue());
        assertEquals(4.0, experiments.loadCandidate(experiment));
        assertThrows(IllegalArgumentException.class,
                () -> experiments.stage("myhelper.auth.api-key", 1, "must never be allowed"));
    }

    private ConfigCatalogService catalogAt(Path root) {
        ConfigCatalogService catalog = new ConfigCatalogService();
        ReflectionTestUtils.setField(catalog, "configRoot", root.toString());
        catalog.refresh();
        assertFalse(catalog.list().isEmpty());
        return catalog;
    }

    @SuppressWarnings("unchecked")
    private Number numericValue(Path file, String... path) throws Exception {
        Map<String, Object> current = new Yaml(new SafeConstructor(new LoaderOptions()))
                .load(Files.readString(file));
        for (int index = 0; index < path.length - 1; index++) {
            current = (Map<String, Object>) current.get(path[index]);
        }
        return (Number) current.get(path[path.length - 1]);
    }
}
