/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.dubbo.config.spring.extension;

import org.apache.dubbo.common.context.Lifecycle;
import org.apache.dubbo.common.extension.ExtensionFactory;
import org.apache.dubbo.common.extension.SPI;
import org.apache.dubbo.common.logger.Logger;
import org.apache.dubbo.common.logger.LoggerFactory;
import org.apache.dubbo.common.utils.ConcurrentHashSet;
import org.apache.dubbo.config.DubboShutdownHook;

import com.alibaba.spring.util.BeanFactoryUtils;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;

import java.util.Set;

/**
 * SpringExtensionFactory
 */
public class SpringExtensionFactory implements ExtensionFactory, Lifecycle {
    private static final Logger logger = LoggerFactory.getLogger(SpringExtensionFactory.class);

    private static final Set<ApplicationContext> CONTEXTS = new ConcurrentHashSet<ApplicationContext>();

    public static void addApplicationContext(ApplicationContext context) {
        CONTEXTS.add(context);
        if (context instanceof ConfigurableApplicationContext) {
            ((ConfigurableApplicationContext) context).registerShutdownHook();
            // see https://github.com/apache/dubbo/issues/7093
            DubboShutdownHook.getDubboShutdownHook().unregister();
        }
    }

    public static void removeApplicationContext(ApplicationContext context) {
        CONTEXTS.remove(context);
    }

    public static Set<ApplicationContext> getContexts() {
        return CONTEXTS;
    }

    // currently for test purpose
    public static void clearContexts() {
        CONTEXTS.clear();
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T> T getExtension(Class<T> type, String name) {

        //SPI should be get from SpiExtensionFactory
        // 若当前type为SPI接口，则直接结束
        if (type.isInterface() && type.isAnnotationPresent(SPI.class)) {
            return null;
        }

        // 遍历所有Spring容器，
        for (ApplicationContext context : CONTEXTS) {
            // 先从当前遍历容器中尝试着获取指定name的bean，
            // 若没有，则再尝试着获取指定type的bean
            T bean = BeanFactoryUtils.getOptionalBean(context, name, type);
            if (bean != null) {
                return bean;
            }
        }

        //logger.warn("No spring extension (bean) named:" + name + ", try to find an extension (bean) of type " + type.getName());

        return null;
    }

    @Override
    public void initialize() throws IllegalStateException {
        clearContexts();
    }

    @Override
    public void start() throws IllegalStateException {
        // no op
    }

    @Override
    public void destroy() {
        clearContexts();
    }
}
