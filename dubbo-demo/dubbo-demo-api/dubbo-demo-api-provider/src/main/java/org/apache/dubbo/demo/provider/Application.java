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
package org.apache.dubbo.demo.provider;

import org.apache.dubbo.config.*;
import org.apache.dubbo.config.bootstrap.DubboBootstrap;
import org.apache.dubbo.demo.DemoService;

import java.util.Arrays;
import java.util.Collections;
import java.util.concurrent.CountDownLatch;

import static org.apache.dubbo.common.constants.CommonConstants.DUBBO_VERSION_KEY;

public class Application {
    public static void main(String[] args) throws Exception {
//        if (isClassic(args)) {
            startWithExport();
//        } else {
//            startWithBootstrap();
//        }
    }

    private static boolean isClassic(String[] args) {
        return args.length > 0 && "classic".equalsIgnoreCase(args[0]);
    }

    private static void startWithBootstrap() {
        ServiceConfig<DemoServiceImpl> service = new ServiceConfig<>();
        service.setInterface(DemoService.class);
        service.setRef(new DemoServiceImpl());

        DubboBootstrap bootstrap = DubboBootstrap.getInstance();
        bootstrap.application(new ApplicationConfig("dubbo-demo-api-provider"))
                .registry(new RegistryConfig("zookeeper://127.0.0.1:2181"))
                .service(service)
                .start()
                .await();
        System.out.println("dubbo service started");
    }

    private static void startWithExport() throws InterruptedException {
        ServiceConfig<DemoServiceImpl> service = new ServiceConfig<>();
        ProtocolConfig protocolConfig = new ProtocolConfig();
        protocolConfig.setThreads(100);
        protocolConfig.setThreads(100);
        protocolConfig.setName(DUBBO_VERSION_KEY);
        service.setInterface(DemoService.class);
        service.setRef(new DemoServiceImpl());
        service.setApplication(new ApplicationConfig("dubbo-demo-api-provider"));
        service.setRegistry(new RegistryConfig("zookeeper://127.0.0.1:2181"));
        ModuleConfig moduleConfig = new ModuleConfig();
        moduleConfig.setRegistry(new RegistryConfig());
        service.setModule(moduleConfig);
        MethodConfig methodConfig = new MethodConfig();
        methodConfig.setRetries(10);
        methodConfig.setName("sayHello");
        ArgumentConfig argumentConfig = new ArgumentConfig();
        argumentConfig.setIndex(0);
        argumentConfig.setType("java.lang.String");
        methodConfig.setArguments(Collections.singletonList(argumentConfig));
        methodConfig.setReturn(true);
        service.setMethods(Collections.singletonList(methodConfig));
        service.setProtocol(protocolConfig);
        service.setProxy("jdk");
        service.setStub(true);
        service.setTimeout(1000000);
        service.export();
        System.out.println("dubbo service started");
        new CountDownLatch(1).await();
    }
}
