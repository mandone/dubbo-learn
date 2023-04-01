package org.apache.dubbo.demo.provider;

import com.alibaba.nacos.spring.context.annotation.config.EnableNacosConfig;
import com.alibaba.nacos.spring.context.annotation.config.NacosPropertySource;
import org.springframework.beans.factory.annotation.Configurable;
import org.springframework.context.annotation.Configuration;

@Configurable
@Configuration
@EnableNacosConfig
@NacosPropertySource(dataId = "dubbo-config-center-nacos.properties",autoRefreshed = true,groupId = "DEFAULT_GROUP")
public class NacosConfig {
}
