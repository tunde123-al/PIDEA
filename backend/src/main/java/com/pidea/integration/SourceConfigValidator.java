package com.pidea.integration;

import com.pidea.common.ValidationException;
import java.net.URI;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/**
 * Validates the free-form config map: flat, bounded, scalar values only, no credential-looking keys.
 * Rejecting secrets here is a guard rail: credentials belong in the environment / a secrets manager.
 */
@Component
public class SourceConfigValidator {
    private static final Pattern KEY = Pattern.compile("^[a-zA-Z][a-zA-Z0-9_.-]{0,49}$");
    private static final Pattern SECRET_LIKE = Pattern.compile("(?i)(password|passwd|secret|token|apikey|api_key|credential|private)");
    private static final int MAX_ENTRIES = 20;
    private static final int MAX_VALUE_LENGTH = 500;
    private static final Set<String> HTTP_API_KEYS =
        Set.of("baseUrl", "pageParam", "pageSizeParam", "pageSize", "maxPages", "timeoutSeconds");

    public Map<String, Object> validate(SourceType type, Map<String, Object> config) {
        Map<String, Object> clean = new LinkedHashMap<>();
        if (config == null) config = Map.of();
        if (config.size() > MAX_ENTRIES) {
            throw new ValidationException("config may contain at most " + MAX_ENTRIES + " entries");
        }
        Map<String, Object> submitted = config;
        submitted.forEach((k, v) -> {
            if (!KEY.matcher(k).matches()) {
                throw new ValidationException("Invalid config key: " + k);
            }
            if (SECRET_LIKE.matcher(k).find()) {
                throw new ValidationException("config key '" + k + "' looks like a credential; use secretRef instead");
            }
            if (!(v instanceof String || v instanceof Number || v instanceof Boolean)) {
                throw new ValidationException("config value for '" + k + "' must be a string, number or boolean");
            }
            if (v instanceof String s && s.length() > MAX_VALUE_LENGTH) {
                throw new ValidationException("config value for '" + k + "' is too long");
            }
            clean.put(k, v);
        });
        if (type == SourceType.HTTP_API) {
            if (!HTTP_API_KEYS.containsAll(clean.keySet())) {
                throw new ValidationException("HTTP_API config supports only baseUrl and bounded pagination settings");
            }
            validateBaseUrl(clean.get("baseUrl"));
            validateInteger(clean, "pageSize", 1, 1000);
            validateInteger(clean, "maxPages", 1, 1000);
            validateInteger(clean, "timeoutSeconds", 1, 60);
            validateParameterName(clean, "pageParam", "page");
            validateParameterName(clean, "pageSizeParam", "size");
            String pageParam = clean.getOrDefault("pageParam", "page").toString();
            String sizeParam = clean.getOrDefault("pageSizeParam", "size").toString();
            if (pageParam.equals(sizeParam)) {
                throw new ValidationException("pageParam and pageSizeParam must be different");
            }
        }
        return clean;
    }

    private static void validateInteger(Map<String, Object> config, String key, int min, int max) {
        Object value = config.get(key);
        if (value == null) return;
        if (!(value instanceof Number number) || number.doubleValue() != number.intValue()
                || number.intValue() < min || number.intValue() > max) {
            throw new ValidationException(key + " must be an integer between " + min + " and " + max);
        }
    }

    private static void validateParameterName(Map<String, Object> config, String key, String defaultValue) {
        Object value = config.get(key);
        String name = value == null ? defaultValue : value.toString();
        if (!name.matches("^[A-Za-z][A-Za-z0-9_-]{0,49}$")) {
            throw new ValidationException(key + " must be a safe query parameter name");
        }
    }

    /** Syntactic check only. Runtime SSRF protection (DNS/IP checks) lives in the HTTP adapter. */
    private void validateBaseUrl(Object value) {
        if (!(value instanceof String s)) {
            throw new ValidationException("HTTP_API sources require a 'baseUrl' config entry");
        }
        try {
            URI u = URI.create(s);
            if (!"https".equalsIgnoreCase(u.getScheme()) || u.getHost() == null || u.getUserInfo() != null) {
                throw new ValidationException("baseUrl must be an https URL without embedded credentials");
            }
        } catch (IllegalArgumentException e) {
            throw new ValidationException("baseUrl is not a valid URL");
        }
    }
}
