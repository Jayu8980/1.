package com.automation.utils;

import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;

/**
 * Reads values from src/test/resources/config.properties.
 * A value can be overridden from the command line, e.g.
 *   mvn test -Ddevice.name=R58M123ABC
 */
public final class ConfigReader {

    private static final Properties PROPS = new Properties();

    static {
        try (InputStream in = ConfigReader.class.getClassLoader()
                .getResourceAsStream("config.properties")) {
            if (in == null) {
                throw new IllegalStateException("config.properties not found in src/test/resources");
            }
            PROPS.load(in);
        } catch (IOException e) {
            throw new IllegalStateException("Could not read config.properties", e);
        }
    }

    private ConfigReader() {
    }

    public static String get(String key) {
        String value = System.getProperty(key, PROPS.getProperty(key));
        return value == null ? "" : value.trim();
    }

    public static int getInt(String key) {
        return Integer.parseInt(get(key));
    }
}
