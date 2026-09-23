package com.habittracker.lambda.env;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import org.apache.commons.logging.Log;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.boot.logging.DeferredLogFactory;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.ssm.SsmClient;
import software.amazon.awssdk.services.ssm.SsmClientBuilder;
import software.amazon.awssdk.services.ssm.model.GetParameterRequest;

public class SsmBackedPropertiesEnvironmentPostProcessor
        implements EnvironmentPostProcessor, Ordered {
    static final String PROPERTY_SOURCE_NAME = "ssmBackedProperties";
    static final String SPECIFICATION_PROPERTY = "SSM_BACKED_PROPERTIES";

    private static final String REGION_PROPERTY = "aws.region";

    private final Log log;

    public SsmBackedPropertiesEnvironmentPostProcessor(DeferredLogFactory logFactory) {
        this.log = logFactory.getLog(SsmBackedPropertiesEnvironmentPostProcessor.class);
    }

    @Override
    public int getOrder() {
        return Ordered.LOWEST_PRECEDENCE;
    }

    @Override
    public void postProcessEnvironment(
            ConfigurableEnvironment environment, SpringApplication application) {
        Map<String, String> parameterPaths = parse(environment.getProperty(SPECIFICATION_PROPERTY));
        if (parameterPaths.isEmpty()) {
            return;
        }
        Map<String, Object> resolved = resolve(parameterPaths, environment);
        environment
                .getPropertySources()
                .addFirst(new MapPropertySource(PROPERTY_SOURCE_NAME, resolved));
        log.info("Resolved " + resolved.size() + " properties from SSM Parameter Store");
    }

    static Map<String, String> parse(String specification) {
        Map<String, String> parameterPaths = new LinkedHashMap<>();
        if (specification == null || specification.isBlank()) {
            return parameterPaths;
        }
        for (String line : specification.split("\\R")) {
            String trimmed = line.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            int separator = trimmed.indexOf('=');
            if (separator <= 0 || separator == trimmed.length() - 1) {
                throw new IllegalStateException(
                        SPECIFICATION_PROPERTY + " entry is not name=path: " + trimmed);
            }
            parameterPaths.put(
                    trimmed.substring(0, separator).trim(),
                    trimmed.substring(separator + 1).trim());
        }
        return parameterPaths;
    }

    private Map<String, Object> resolve(
            Map<String, String> parameterPaths, ConfigurableEnvironment environment) {
        Map<String, Object> resolved = new HashMap<>();
        try (SsmClient ssmClient = ssmClient(environment)) {
            parameterPaths.forEach(
                    (propertyName, parameterPath) ->
                            resolved.put(
                                    propertyName, fetch(ssmClient, propertyName, parameterPath)));
        }
        return resolved;
    }

    private String fetch(SsmClient ssmClient, String propertyName, String parameterPath) {
        try {
            return ssmClient
                    .getParameter(
                            GetParameterRequest.builder()
                                    .name(parameterPath)
                                    .withDecryption(true)
                                    .build())
                    .parameter()
                    .value();
        } catch (RuntimeException e) {
            throw new IllegalStateException(
                    "Could not resolve "
                            + propertyName
                            + " from SSM parameter "
                            + parameterPath
                            + ": the execution role needs ssm:GetParameter and kms:Decrypt on it",
                    e);
        }
    }

    private SsmClient ssmClient(ConfigurableEnvironment environment) {
        String region = environment.getProperty(REGION_PROPERTY);
        SsmClientBuilder builder = SsmClient.builder();
        if (region != null && !region.isBlank()) {
            builder.region(Region.of(region));
        }
        return builder.build();
    }
}
