/*
 * Copyright 2023 LINE Corporation
 *
 * LINE Corporation licenses this file to you under the Apache License,
 * version 2.0 (the "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at:
 *
 *   https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */
package com.linecorp.armeria.server.healthcheck;

import static java.util.Objects.requireNonNull;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.management.ManagementFactory;
import java.lang.management.OperatingSystemMXBean;
import java.lang.reflect.Method;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.DoubleSupplier;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.ImmutableList;

import com.linecorp.armeria.common.annotation.Nullable;

/**
 * A {@link HealthChecker} that reports as unhealthy when the current
 * CPU usage or CPU load exceeds threshold. For example:
 * <pre>{@code
 * final CpuHealthChecker cpuHealthChecker = HealthChecker.of(10, 10);<br>
 * final boolean healthy = cpuHealthChecker.isHealthy();
 * }</pre>
 */
// Forked from <a href="https://github.com/micrometer-metrics/micrometer/blob/8339d57bef8689beb8d7a18b429a166f6595f2af/micrometer-core/src/main/java/io/micrometer/core/instrument/binder/system/ProcessorMetrics.java">ProcessorMetrics.java</a> in the micrometer core.
final class CpuHealthChecker implements HealthChecker {

    private static final DoubleSupplier currentSystemCpuUsageSupplier;

    private static final DoubleSupplier currentProcessCpuUsageSupplier;

    private static final OperatingSystemMXBean operatingSystemBean;

    private static final Class<?> operatingSystemBeanClass;

    private static final List<String> OPERATING_SYSTEM_BEAN_CLASS_NAMES = ImmutableList.of(
            "com.ibm.lang.management.OperatingSystemMXBean", // J9
            "com.sun.management.OperatingSystemMXBean" // HotSpot
    );

    @Nullable
    @VisibleForTesting
    static final Method systemCpuUsage;

    @Nullable
    @VisibleForTesting
    static final Method processCpuUsage;

    static {
        operatingSystemBeanClass = requireNonNull(getFirstClassFound(OPERATING_SYSTEM_BEAN_CLASS_NAMES));
        operatingSystemBean = ManagementFactory.getOperatingSystemMXBean();
        systemCpuUsage = detectMethod("getSystemCpuLoad");
        processCpuUsage = detectMethod("getProcessCpuLoad");
        currentSystemCpuUsageSupplier = () -> invoke(systemCpuUsage);
        currentProcessCpuUsageSupplier = () -> invoke(processCpuUsage);
    }

    private final double targetCpuUsage;

    private final DoubleSupplier systemCpuUsageSupplier;

    private final DoubleSupplier processCpuUsageSupplier;

    private final double targetProcessCpuLoad;

    private static final MethodHandles.Lookup LOOKUP = MethodHandles.lookup();

    private static final ConcurrentHashMap<String, MethodHandle> CACHE = new ConcurrentHashMap<>();

    /**
     * Instantiates a new Default cpu health checker.
     *
     * @param cpuUsageThreshold the target cpu usage
     * @param processCpuLoadThreshold the target process cpu usage
     */
    private CpuHealthChecker(double cpuUsageThreshold, double processCpuLoadThreshold) {
        this(cpuUsageThreshold, processCpuLoadThreshold,
                currentSystemCpuUsageSupplier, currentProcessCpuUsageSupplier);
    }

    private CpuHealthChecker(double cpuUsageThreshold, double processCpuLoadThreshold,
                             DoubleSupplier systemCpuUsageSupplier, DoubleSupplier processCpuUsageSupplier) {
        this.targetCpuUsage = cpuUsageThreshold;
        this.targetProcessCpuLoad = processCpuLoadThreshold;
        this.systemCpuUsageSupplier = systemCpuUsageSupplier;
        this.processCpuUsageSupplier = processCpuUsageSupplier;
    }

    private static double invoke(final Method method) {
        MethodHandle mh = CACHE.get(method.getName());
        if (mh == null) {
            try {
                mh = LOOKUP.unreflect(method);
            } catch (IllegalAccessException e) {
                throw new RuntimeException(e);
            }

            CACHE.put(method.getName(), mh);
        }

        try {
            return (double) mh.invoke(operatingSystemBean);
        } catch (Throwable e) {
            return Double.NaN;
        }
    }

    @Nullable
    private static Method detectMethod(final String name) {
        try {
            // ensure the Bean we have is actually an instance of the interface
            requireNonNull(operatingSystemBeanClass.cast(operatingSystemBean));
            return operatingSystemBeanClass.getMethod(name);
        } catch (ClassCastException | NoSuchMethodException | SecurityException e) {
            return null;
        }
    }

    @Nullable
    private static Class<?> getFirstClassFound(final List<String> classNames) {
        for (String className : classNames) {
            try {
                return Class.forName(className, false, CpuHealthChecker.class.getClassLoader());
            } catch (ClassNotFoundException ignore) {
            }
        }
        return null;
    }

    public static CpuHealthChecker of(double targetCpuUsage, double targetProcessCpuLoad) {
        return new CpuHealthChecker(targetCpuUsage, targetProcessCpuLoad);
    }

    /**
     * Returns true if and only if System CPU Usage and Processes cpu usage is below the target usage.
     */
    @Override
    public boolean isHealthy() {
        final double currentSystemCpuUsage = systemCpuUsageSupplier.getAsDouble();
        final double currentProcessCpuUsage = processCpuUsageSupplier.getAsDouble();
        return currentSystemCpuUsage <= targetCpuUsage && currentProcessCpuUsage <= targetProcessCpuLoad;
    }

    @VisibleForTesting
    boolean isHealthy(
            DoubleSupplier currentSystemCpuUsageSupplier, DoubleSupplier currentProcessCpuUsageSupplier) {
        final double currentSystemCpuUsage = currentSystemCpuUsageSupplier.getAsDouble();
        final double currentProcessCpuUsage = currentProcessCpuUsageSupplier.getAsDouble();
        return currentSystemCpuUsage <= targetCpuUsage && currentProcessCpuUsage <= targetProcessCpuLoad;
    }
}
