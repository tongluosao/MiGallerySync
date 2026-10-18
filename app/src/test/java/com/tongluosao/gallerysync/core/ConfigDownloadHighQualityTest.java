package com.tongluosao.gallerysync.core;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.content.SharedPreferences;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import org.junit.Test;

public class ConfigDownloadHighQualityTest {

    @Test
    public void highQualityDownloadDefaultsOffForExistingDownloadSettings() {
        FakePreferences preferences = new FakePreferences();
        preferences.edit().putBoolean(Config.KEY_DOWNLOAD_ENABLED, true).apply();

        Config config = Config.from(preferences);

        assertTrue(config.isDownloadEnabled());
        assertFalse(config.isDownloadHighQuality());
    }

    @Test
    public void highQualitySettingSurvivesDisablingAutomaticDownload() {
        FakePreferences preferences = new FakePreferences();
        preferences.edit()
                .putBoolean(Config.KEY_DOWNLOAD_ENABLED, true)
                .putBoolean(Config.KEY_DOWNLOAD_HIGH_QUALITY, true)
                .apply();
        assertTrue(Config.from(preferences).isDownloadHighQuality());

        preferences.edit().putBoolean(Config.KEY_DOWNLOAD_ENABLED, false).apply();
        Config config = Config.from(preferences);

        assertFalse(config.isDownloadEnabled());
        assertTrue(config.isDownloadHighQuality());
    }

    private static final class FakePreferences implements SharedPreferences {
        private final Map<String, Object> values = new HashMap<>();

        @Override public Map<String, ?> getAll() { return Collections.unmodifiableMap(values); }
        @Override public String getString(String key, String fallback) {
            Object value = values.get(key);
            return value instanceof String ? (String) value : fallback;
        }
        @SuppressWarnings("unchecked")
        @Override public Set<String> getStringSet(String key, Set<String> fallback) {
            Object value = values.get(key);
            return value instanceof Set<?> ? (Set<String>) value : fallback;
        }
        @Override public int getInt(String key, int fallback) {
            Object value = values.get(key);
            return value instanceof Integer ? (Integer) value : fallback;
        }
        @Override public long getLong(String key, long fallback) {
            Object value = values.get(key);
            return value instanceof Long ? (Long) value : fallback;
        }
        @Override public float getFloat(String key, float fallback) {
            Object value = values.get(key);
            return value instanceof Float ? (Float) value : fallback;
        }
        @Override public boolean getBoolean(String key, boolean fallback) {
            Object value = values.get(key);
            return value instanceof Boolean ? (Boolean) value : fallback;
        }
        @Override public boolean contains(String key) { return values.containsKey(key); }
        @Override public Editor edit() { return new FakeEditor(); }
        @Override public void registerOnSharedPreferenceChangeListener(
                OnSharedPreferenceChangeListener listener) { }
        @Override public void unregisterOnSharedPreferenceChangeListener(
                OnSharedPreferenceChangeListener listener) { }

        private final class FakeEditor implements Editor {
            private final Map<String, Object> updates = new HashMap<>();

            @Override public Editor putString(String key, String value) {
                updates.put(key, value);
                return this;
            }
            @Override public Editor putStringSet(String key, Set<String> value) {
                updates.put(key, value);
                return this;
            }
            @Override public Editor putInt(String key, int value) {
                updates.put(key, value);
                return this;
            }
            @Override public Editor putLong(String key, long value) {
                updates.put(key, value);
                return this;
            }
            @Override public Editor putFloat(String key, float value) {
                updates.put(key, value);
                return this;
            }
            @Override public Editor putBoolean(String key, boolean value) {
                updates.put(key, value);
                return this;
            }
            @Override public Editor remove(String key) {
                updates.put(key, this);
                return this;
            }
            @Override public Editor clear() {
                values.clear();
                return this;
            }
            @Override public boolean commit() {
                apply();
                return true;
            }
            @Override public void apply() {
                for (Map.Entry<String, Object> entry : updates.entrySet()) {
                    if (entry.getValue() == this) {
                        values.remove(entry.getKey());
                    } else {
                        values.put(entry.getKey(), entry.getValue());
                    }
                }
            }
        }
    }
}
