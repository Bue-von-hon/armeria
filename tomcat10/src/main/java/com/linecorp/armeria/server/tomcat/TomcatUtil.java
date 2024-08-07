/*
 * Copyright 2015 LINE Corporation
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

package com.linecorp.armeria.server.tomcat;

import java.io.IOException;
import java.lang.reflect.Method;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicReference;
import java.util.zip.ZipFile;

import org.apache.catalina.Container;
import org.apache.catalina.Engine;
import org.apache.catalina.LifecycleListener;
import org.apache.catalina.Service;
import org.apache.catalina.core.StandardEngine;
import org.apache.catalina.realm.NullRealm;
import org.apache.catalina.startup.Constants;
import org.apache.catalina.startup.Tomcat;
import org.apache.catalina.startup.Tomcat.DefaultWebXmlListener;

import com.linecorp.armeria.common.annotation.Nullable;

final class TomcatUtil {

    private static final LifecycleListener defaultWebXmlListener = new DefaultWebXmlListener();

    @Nullable
    static URL getWebAppConfigFile(String contextPath, Path docBase) {
        final AtomicReference<URL> configUrlRef = new AtomicReference<>();
        new Tomcat() {
            {
                configUrlRef.set(getWebappConfigFile(docBase.toString(), contextPath));
            }
        };

        return configUrlRef.get();
    }

    static String noDefaultWebXmlPath() {
        return Constants.NoDefaultWebXml;
    }

    static LifecycleListener getDefaultWebXmlListener() {
        return defaultWebXmlListener;
    }

    /**
     * Returns the {@link Engine} of the specified Tomcat {@link Service}. If the {@link Service} has no
     * {@link Engine} set, a new {@link Engine} will be created and set.
     *
     * <p>The return type of {@link Service#getContainer()} has been changed from {@link Container} to
     * {@link Engine} since 8.5. Calling it directly will cause {@link NoSuchMethodError}.
     */
    static Engine engine(Service service, String hostname) {
        final Engine engine;
        try {
            final Method m = Service.class.getDeclaredMethod("getContainer");
            engine = (Engine) m.invoke(service);
        } catch (Exception e) {
            throw new Error("failed to invoke Service.getContainer()", e);
        }

        if (engine != null) {
            return engine;
        }

        // Create a new engine.
        final Engine newEngine = new StandardEngine();
        newEngine.setName("Tomcat");
        newEngine.setDefaultHost(hostname);
        newEngine.setRealm(new NullRealm());
        service.setContainer(newEngine);
        return newEngine;
    }

    static boolean isZip(Path path) {
        if (!Files.isRegularFile(path)) {
            return false;
        }

        try (ZipFile ignored = new ZipFile(path.toFile())) {
            return true;
        } catch (IOException ignored) {
            // Probably not a JAR file.
            return false;
        }
    }

    private TomcatUtil() {}
}
