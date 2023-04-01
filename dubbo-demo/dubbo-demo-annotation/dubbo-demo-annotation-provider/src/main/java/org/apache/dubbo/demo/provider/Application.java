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

import com.alibaba.nacos.api.annotation.NacosProperties;
import com.alibaba.nacos.spring.context.annotation.config.EnableNacosConfig;
import com.alibaba.nacos.spring.context.annotation.config.NacosPropertySource;
import org.apache.dubbo.config.spring.context.annotation.EnableDubbo;

import org.springframework.context.annotation.AnnotationConfigApplicationContext;
//启动Dubbo注解扫描
@EnableDubbo(scanBasePackages = "org.apache.dubbo.demo.provider")
//激活Nacos配置并指定Nacos服务所在地址
@EnableNacosConfig(globalProperties = @NacosProperties(serverAddr = "127.0.0.1:8848")) // 激活 Nacos 配置
//指定我们的外部化配置唯一标识：dataId
@NacosPropertySource(dataId = "dubbo-config-center-nacos.properties")
public class Application {
    public static void main(String[] args) throws Exception {
        AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext();
        //注册当前AnnotationApplication为配置类
        context.register(Application.class);
        //刷新Spring上下文
        context.refresh();
        System.in.read();
    }

//    @Configuration
//    @EnableDubbo(scanBasePackages = "org.apache.dubbo.demo.provider")
//    @PropertySource("classpath:/spring/dubbo-provider.properties")
//    static class ProviderConfiguration {
//        @Bean
//        public RegistryConfig registryConfig() {
//            RegistryConfig registryConfig = new RegistryConfig();
//            registryConfig.setAddress("zookeeper://127.0.0.1:2181");
//            return registryConfig;
//        }
//    }
}
