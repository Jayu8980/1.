package com.automation.autotest;

/** One automated test: a name, a group and the steps to run. */
public final class TestCase {

    public interface Body {
        void run(Device d) throws Exception;
    }

    public final String group;
    public final String name;
    public final Body body;

    public TestCase(String group, String name, Body body) {
        this.group = group;
        this.name = name;
        this.body = body;
    }

    // Small assertion helpers, like TestNG's Assert.

    public static void assertTrue(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }

    public static void assertFalse(boolean condition, String message) {
        assertTrue(!condition, message);
    }

    public static void assertEquals(Object actual, Object expected, String message) {
        if (expected == null ? actual != null : !expected.equals(actual)) {
            throw new AssertionError(message + " (expected \"" + expected + "\", got \"" + actual + "\")");
        }
    }
}
