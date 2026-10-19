package com.dom.datamonitor.config;

import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.Constructor;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

/**
 * Loads and parses the YAML configuration file into an {@link AppConfig} object.
 *
 * <p>SnakeYAML 2.x is used for parsing. The {@code AppConfig} and its nested classes
 * must provide JavaBeans-style setters so SnakeYAML can populate them.
 */
public class ConfigLoader {

    private ConfigLoader() {}

    /**
     * Loads configuration from the given file path.
     *
     * @param configFile path to the YAML configuration file
     * @return fully populated {@link AppConfig}
     * @throws ConfigException if the file cannot be read or contains invalid YAML
     */
    public static AppConfig load(File configFile) throws ConfigException {
        if (!configFile.exists()) {
            throw new ConfigException("Configuration file not found: " + configFile.getAbsolutePath());
        }
        if (!configFile.canRead()) {
            throw new ConfigException("Configuration file is not readable: " + configFile.getAbsolutePath());
        }

        try (InputStream in = Files.newInputStream(configFile.toPath());
             Reader reader  = new InputStreamReader(in, StandardCharsets.UTF_8)) {

            LoaderOptions loaderOptions = new LoaderOptions();
            loaderOptions.setAllowDuplicateKeys(false);

            Yaml yaml = new Yaml(new Constructor(AppConfig.class, loaderOptions));
            AppConfig config = yaml.load(reader);

            if (config == null) {
                throw new ConfigException("Configuration file is empty: " + configFile.getName());
            }
            return config;

        } catch (ConfigException e) {
            throw e;
        } catch (Exception e) {
            throw new ConfigException(
                "Failed to parse configuration file '" + configFile.getName() + "': " + e.getMessage(), e);
        }
    }

    /**
     * Loads configuration from a classpath resource (useful for testing).
     */
    public static AppConfig loadFromResource(String resourcePath) throws ConfigException {
        InputStream in = ConfigLoader.class.getResourceAsStream(resourcePath);
        if (in == null) {
            throw new ConfigException("Configuration resource not found: " + resourcePath);
        }
        try (Reader reader = new InputStreamReader(in, StandardCharsets.UTF_8)) {
            LoaderOptions loaderOptions = new LoaderOptions();
            Yaml yaml = new Yaml(new Constructor(AppConfig.class, loaderOptions));
            AppConfig config = yaml.load(reader);
            if (config == null) {
                throw new ConfigException("Configuration resource is empty: " + resourcePath);
            }
            return config;
        } catch (ConfigException e) {
            throw e;
        } catch (Exception e) {
            throw new ConfigException(
                "Failed to parse configuration resource '" + resourcePath + "': " + e.getMessage(), e);
        }
    }
}
