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
package org.apache.dubbo.config;

import org.apache.dubbo.common.URL;
import org.apache.dubbo.common.URLBuilder;
import org.apache.dubbo.common.Version;
import org.apache.dubbo.common.bytecode.Wrapper;
import org.apache.dubbo.common.extension.ExtensionLoader;
import org.apache.dubbo.common.logger.Logger;
import org.apache.dubbo.common.logger.LoggerFactory;
import org.apache.dubbo.common.utils.CollectionUtils;
import org.apache.dubbo.common.utils.ConfigUtils;
import org.apache.dubbo.common.utils.NamedThreadFactory;
import org.apache.dubbo.common.utils.StringUtils;
import org.apache.dubbo.config.annotation.Service;
import org.apache.dubbo.config.bootstrap.DubboBootstrap;
import org.apache.dubbo.config.event.ServiceConfigExportedEvent;
import org.apache.dubbo.config.event.ServiceConfigUnexportedEvent;
import org.apache.dubbo.config.invoker.DelegateProviderMetaDataInvoker;
import org.apache.dubbo.config.support.Parameter;
import org.apache.dubbo.config.utils.ConfigValidationUtils;
import org.apache.dubbo.event.Event;
import org.apache.dubbo.event.EventDispatcher;
import org.apache.dubbo.metadata.ServiceNameMapping;
import org.apache.dubbo.registry.client.metadata.MetadataUtils;
import org.apache.dubbo.rpc.Exporter;
import org.apache.dubbo.rpc.Invoker;
import org.apache.dubbo.rpc.Protocol;
import org.apache.dubbo.rpc.ProxyFactory;
import org.apache.dubbo.rpc.cluster.ConfiguratorFactory;
import org.apache.dubbo.rpc.filter.TokenFilter;
import org.apache.dubbo.rpc.model.ApplicationModel;
import org.apache.dubbo.rpc.model.ServiceDescriptor;
import org.apache.dubbo.rpc.model.ServiceRepository;
import org.apache.dubbo.rpc.proxy.wrapper.StubProxyFactoryWrapper;
import org.apache.dubbo.rpc.service.GenericService;
import org.apache.dubbo.rpc.support.ProtocolUtils;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import static org.apache.dubbo.common.constants.CommonConstants.ANYHOST_KEY;
import static org.apache.dubbo.common.constants.CommonConstants.ANY_VALUE;
import static org.apache.dubbo.common.constants.CommonConstants.DUBBO;
import static org.apache.dubbo.common.constants.CommonConstants.DUBBO_IP_TO_BIND;
import static org.apache.dubbo.common.constants.CommonConstants.LOCALHOST_VALUE;
import static org.apache.dubbo.common.constants.CommonConstants.MAPPING_KEY;
import static org.apache.dubbo.common.constants.CommonConstants.METADATA_KEY;
import static org.apache.dubbo.common.constants.CommonConstants.METHODS_KEY;
import static org.apache.dubbo.common.constants.CommonConstants.MONITOR_KEY;
import static org.apache.dubbo.common.constants.CommonConstants.PROVIDER_SIDE;
import static org.apache.dubbo.common.constants.CommonConstants.REGISTER_KEY;
import static org.apache.dubbo.common.constants.CommonConstants.REMOTE_METADATA_STORAGE_TYPE;
import static org.apache.dubbo.common.constants.CommonConstants.REVISION_KEY;
import static org.apache.dubbo.common.constants.CommonConstants.SERVICE_NAME_MAPPING_KEY;
import static org.apache.dubbo.common.constants.CommonConstants.SIDE_KEY;
import static org.apache.dubbo.common.constants.RegistryConstants.DYNAMIC_KEY;
import static org.apache.dubbo.common.constants.RegistryConstants.SERVICE_REGISTRY_PROTOCOL;
import static org.apache.dubbo.common.utils.NetUtils.getAvailablePort;
import static org.apache.dubbo.common.utils.NetUtils.getLocalHost;
import static org.apache.dubbo.common.utils.NetUtils.isInvalidLocalHost;
import static org.apache.dubbo.common.utils.NetUtils.isInvalidPort;
import static org.apache.dubbo.config.Constants.DUBBO_IP_TO_REGISTRY;
import static org.apache.dubbo.config.Constants.DUBBO_PORT_TO_BIND;
import static org.apache.dubbo.config.Constants.DUBBO_PORT_TO_REGISTRY;
import static org.apache.dubbo.config.Constants.SCOPE_NONE;
import static org.apache.dubbo.remoting.Constants.BIND_IP_KEY;
import static org.apache.dubbo.remoting.Constants.BIND_PORT_KEY;
import static org.apache.dubbo.rpc.Constants.GENERIC_KEY;
import static org.apache.dubbo.rpc.Constants.LOCAL_PROTOCOL;
import static org.apache.dubbo.rpc.Constants.PROXY_KEY;
import static org.apache.dubbo.rpc.Constants.SCOPE_KEY;
import static org.apache.dubbo.rpc.Constants.SCOPE_LOCAL;
import static org.apache.dubbo.rpc.Constants.SCOPE_REMOTE;
import static org.apache.dubbo.rpc.Constants.TOKEN_KEY;
import static org.apache.dubbo.rpc.cluster.Constants.EXPORT_KEY;

public class ServiceConfig<T> extends ServiceConfigBase<T> {

    private static final long serialVersionUID = -412700146501624375L;

    public static final Logger logger = LoggerFactory.getLogger(ServiceConfig.class);

    /**
     * A random port cache, the different protocols who has no port specified have different random port
     */
    private static final Map<String, Integer> RANDOM_PORT_MAP = new HashMap<String, Integer>();

    /**
     * A delayed exposure service timer
     */
    private static final ScheduledExecutorService DELAY_EXPORT_EXECUTOR =
            Executors.newSingleThreadScheduledExecutor(new NamedThreadFactory("DubboServiceDelayExporter", true));

    private String serviceName;

    private static final Protocol PROTOCOL = ExtensionLoader.getExtensionLoader(Protocol.class).getAdaptiveExtension();

    /**
     * A {@link ProxyFactory} implementation that will generate a exported service proxy,the JavassistProxyFactory is its
     * default implementation
     */
    private static final ProxyFactory PROXY_FACTORY = ExtensionLoader.getExtensionLoader(ProxyFactory.class).getAdaptiveExtension();

    /**
     * Whether the provider has been exported
     */
    private transient volatile boolean exported;

    /**
     * The flag whether a service has unexported ,if the method unexported is invoked, the value is true
     */
    private transient volatile boolean unexported;

    private DubboBootstrap bootstrap;

    /**
     * The exported services
     */
    private final List<Exporter<?>> exporters = new ArrayList<Exporter<?>>();

    private static final String CONFIG_INITIALIZER_PROTOCOL = "configInitializer://";

    private static final String TRUE_VALUE = "true";

    private static final String FALSE_VALUE = "false";

    private static final String STUB_SUFFIX = "Stub";

    private static final String LOCAL_SUFFIX = "Local";

    private static final String CONFIG_POST_PROCESSOR_PROTOCOL = "configPostProcessor://";

    private static final String RETRY_SUFFIX = ".retry";

    private static final String RETRIES_SUFFIX = ".retries";

    private static final String ZERO_VALUE = "0";

    public ServiceConfig() {
    }

    public ServiceConfig(Service service) {
        super(service);
    }

    @Parameter(excluded = true)
    @Override
    public boolean isExported() {
        return exported;
    }

    @Parameter(excluded = true)
    @Override
    public boolean isUnexported() {
        return unexported;
    }

    @Override
    public void unexport() {
        if (!exported) {
            return;
        }
        if (unexported) {
            return;
        }
        if (!exporters.isEmpty()) {
            for (Exporter<?> exporter : exporters) {
                try {
                    exporter.unexport();
                } catch (Throwable t) {
                    logger.warn("Unexpected error occurred when unexport " + exporter, t);
                }
            }
            exporters.clear();
        }
        unexported = true;

        // dispatch a ServiceConfigUnExportedEvent since 2.7.4
        dispatch(new ServiceConfigUnexportedEvent(this));
    }

    @Override
    public synchronized void export() {
        if (bootstrap == null) {//第一次发布，通过单例创建DubboBootStrap对象
            bootstrap = DubboBootstrap.getInstance();
            // compatible with api call.
            if (null != this.getRegistry()) {
                bootstrap.registries(this.getRegistries());
            }
            bootstrap.initialize();
        }

        checkAndUpdateSubConfigs();
        //初始化元数据服务
        initServiceMetadata(provider);
        serviceMetadata.setServiceType(getInterfaceClass());
        serviceMetadata.setTarget(getRef());
        //org.apache.dubbo.demo.DemoService
        serviceMetadata.generateServiceKey();

        if (!shouldExport()) {
            return;
        }
        //是否延迟发布，使用定时任务
        if (shouldDelay()) {
            DELAY_EXPORT_EXECUTOR.schedule(() -> {
                try {
                    // Delay export server should print stack trace if there are exception occur.
                    this.doExport();
                } catch (Exception e) {
                    logger.error("delay export server occur exception, please check it.", e);
                }
            }, getDelay(), TimeUnit.MILLISECONDS);
        } else {
            //进入发布流程 synchronized 加锁，要维护exported和unexported状态
            doExport();
        }
        // dispatch(new ServiceConfigExportedEvent(this));
        exported();
    }

    public void exported() {
        List<URL> exportedURLs = this.getExportedUrls();
        exportedURLs.forEach(url -> {
            // dubbo2.7.x does not register serviceNameMapping information with the registry by default.
            // Only when the user manually turns on the service introspection, can he register with the registration center.
            if (url.getParameters().containsKey(SERVICE_NAME_MAPPING_KEY)) {
                Map<String, String> parameters = getApplication().getParameters();
                ServiceNameMapping.getExtension(parameters != null ? parameters.get(MAPPING_KEY) : null).map(url);
            }
        });
        // dispatch a ServiceConfigExportedEvent since 2.7.4
        dispatch(new ServiceConfigExportedEvent(this));
    }

    private void checkAndUpdateSubConfigs() {
        //ServiceConfig: ProviderConfig是针对所有暴露服务的接口，而ServiceConfig则针对具体的一个接口，比如接口名、实现类等
        completeCompoundConfigs();
        checkDefault();
        //convertProtocolIdsToProtocols
        checkProtocol();
        // init some null configuration.
        List<ConfigInitializer> configInitializers = ExtensionLoader.getExtensionLoader(ConfigInitializer.class)
                .getActivateExtension(URL.valueOf(CONFIG_INITIALIZER_PROTOCOL), (String[]) null);
        configInitializers.forEach(e -> e.initServiceConfig(this));

        // if protocol is not injvm checkRegistry LOCAL_PROTOCOL
        if (!isOnlyInJvm()) {
            //computeValidRegistryIds，check registryConfig ，set registries
            checkRegistry();
        }
        this.refresh();

        if (StringUtils.isEmpty(interfaceName)) {
            throw new IllegalStateException("<dubbo:service interface=\"\" /> interface not allow null!");
        }
        //是否是泛化调用，如果是就更新generic值为true
        if (ref instanceof GenericService) {
            interfaceClass = GenericService.class;
            if (StringUtils.isEmpty(generic)) {
                generic = TRUE_VALUE;
            }
        } else {
            try {
                interfaceClass = Class.forName(interfaceName, true, Thread.currentThread()
                        .getContextClassLoader());
            } catch (ClassNotFoundException e) {
                throw new IllegalStateException(e.getMessage(), e);
            }
            checkInterfaceAndMethods(interfaceClass, getMethods());
            checkRef();
            generic = FALSE_VALUE;
        }

        /**
         * 1、校验stub和local实现类 【本地存根】，这里local已经被stub替换，参考：StubProxyFactoryWrapper，使用stub机制可以实现类似aop操作
         * @see org.apache.dubbo.rpc.proxy.wrapper.StubProxyFactoryWrapper
         * 2、校验mock 模拟调用和返回结果
         * @see org.apache.dubbo.rpc.cluster.support.wrapper.MockClusterInvoker
         *
         * @see org.apache.dubbo.registry.integration.RegistryDirectory#mergeUrl(org.apache.dubbo.common.URL)
         */
        checkStubAndLocal(interfaceClass);
        ConfigValidationUtils.checkMock(interfaceClass, this);
        /**
         * 校验serviceConfig
         * 1、version、group、token、path
         * 2、listener 扩展类 String config.getListener(){}
         * 3、抽象配置类：代理、集群、文件等 List<MethodConfig>
         * 4、校验RegistryConfig集合 --> 多注册中心
         * 5、校验ProtocolConfig集合 --> 多协议
         * 6、校验providerConfig
         */
        ConfigValidationUtils.validateServiceConfig(this);
        //configPostProcessor:// ConfigPostProcessor 使用spi机制，可以由我们自行实现，此处会执行postProcessServiceConfig方法
        postProcessConfig();
    }


    protected synchronized void doExport() {
        if (unexported) {
            throw new IllegalStateException("The service " + interfaceClass.getName() + " has already unexported!");
        }
        if (exported) {
            return;
        }
        exported = true;
        //默认path为接口全路径
        if (StringUtils.isEmpty(path)) {
            path = interfaceName;
        }
        //利用url参数进行发布，将一切配置转成url，最终注册到注册中心
        doExportUrls();
        //更新bootstrap状态
        bootstrap.setReady(true);
    }

    private void doExportUrls() {
        //将provider和service进行本地存储，保存到ServiceRepository类种
        ServiceRepository repository = ApplicationModel.getServiceRepository();
        ServiceDescriptor serviceDescriptor = repository.registerService(getInterfaceClass());
        repository.registerProvider(
                getUniqueServiceName(),
                ref,
                serviceDescriptor,
                this,
                serviceMetadata
        );
        /**
         * 1、将配置转换成url集合，interfaceConfig.getRegistries()获取所有注册中心配置
         * 2、读取address参数，默认 0.0.0.0
         * 3、appendParameters方法将配置转成map
         * 4、UrlUtils.parseURLs(address, map);将map再转成Url集合
         *
         */
        List<URL> registryURLs = ConfigValidationUtils.loadRegistries(this, true);

        int protocolConfigNum = protocols.size();
        //支持多协议，每种协议同时又支持多个注册中心registryURLs
        for (ProtocolConfig protocolConfig : protocols) {
            // groupName/org.apache.dubbo.demo.DemoService:version
            String pathKey = URL.buildKey(getContextPath(protocolConfig)
                    .map(p -> p + "/" + path)
                    .orElse(path), group, version);
            // In case user specified path, register service one more time to map it to path. 重新注册
            repository.registerService(pathKey, interfaceClass);
            //通过ProtocolConfig和注册中心地址 进去服务暴露核心逻辑
            doExportUrlsFor1Protocol(protocolConfig, registryURLs, protocolConfigNum);
        }
    }

    private void doExportUrlsFor1Protocol(ProtocolConfig protocolConfig, List<URL> registryURLs, int protocolConfigNum) {
        String name = protocolConfig.getName();
        if (StringUtils.isEmpty(name)) {
            name = DUBBO;
        }

        Map<String, String> map = new HashMap<String, String>();
        map.put(SIDE_KEY, PROVIDER_SIDE);//指明是服务提供端
        //进程id、版本、时间戳
        //org.apache.dubbo.config.AbstractConfig.appendParameters()
        ServiceConfig.appendRuntimeParameters(map);
        AbstractConfig.appendParameters(map, getMetrics());//已废弃
        AbstractConfig.appendParameters(map, getApplication());//applicationName
        AbstractConfig.appendParameters(map, getModule());
        // remove 'default.' prefix for configs from ProviderConfig
        // appendParameters(map, provider, Constants.DEFAULT_KEY);
        AbstractConfig.appendParameters(map, provider);
        AbstractConfig.appendParameters(map, protocolConfig);
        AbstractConfig.appendParameters(map, this);
        MetadataReportConfig metadataReportConfig = getMetadataReportConfig();
        if (metadataReportConfig != null && metadataReportConfig.isValid()) {
            map.putIfAbsent(METADATA_KEY, REMOTE_METADATA_STORAGE_TYPE);
        }
        //处理methodConfig -> 一个service下会有多个method
        if (CollectionUtils.isNotEmpty(getMethods())) {//retry处理逻辑，如果有设置方法
            for (MethodConfig method : getMethods()) {
                AbstractConfig.appendParameters(map, method, method.getName());
                String retryKey = method.getName() + RETRY_SUFFIX;
                if (map.containsKey(retryKey)) {
                    String retryValue = map.remove(retryKey);
                    if (FALSE_VALUE.equals(retryValue)) {
                        map.put(method.getName() + RETRIES_SUFFIX, ZERO_VALUE);
                    }
                }
                //处理argumentConfig  一个method -> 多个argument
                List<ArgumentConfig> arguments = method.getArguments();
                if (CollectionUtils.isNotEmpty(arguments)) {
                    for (ArgumentConfig argument : arguments) {
                        // convert argument type
                        if (argument.getType() != null && argument.getType().length() > 0) {
                            Method[] methods = interfaceClass.getMethods();
                            // visit all methods
                            if (methods.length > 0) {
                                for (int i = 0; i < methods.length; i++) {
                                    String methodName = methods[i].getName();
                                    // target the method, and get its signature
                                    if (methodName.equals(method.getName())) {
                                        Class<?>[] argtypes = methods[i].getParameterTypes();
                                        // one callback in the method
                                        if (argument.getIndex() != -1) {
                                            //参数配置type要和实际dubbo定义的接口全路径类名保持一致
                                            if (argtypes[argument.getIndex()].getName().equals(argument.getType())) {
                                                AbstractConfig
                                                        .appendParameters(map, argument, method.getName() + "." + argument.getIndex());
                                            } else {
                                                throw new IllegalArgumentException(
                                                        "Argument config error : the index attribute and type attribute not match :index :" +
                                                                argument.getIndex() + ", type:" + argument.getType());
                                            }
                                        } else {
                                            // multiple callbacks in the method
                                            for (int j = 0; j < argtypes.length; j++) {
                                                Class<?> argclazz = argtypes[j];
                                                if (argclazz.getName().equals(argument.getType())) {
                                                    AbstractConfig.appendParameters(map, argument, method.getName() + "." + j);
                                                    if (argument.getIndex() != -1 && argument.getIndex() != j) {
                                                        throw new IllegalArgumentException(
                                                                "Argument config error : the index attribute and type attribute not match :index :" +
                                                                        argument.getIndex() + ", type:" + argument.getType());
                                                    }
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        } else if (argument.getIndex() != -1) {
                            AbstractConfig.appendParameters(map, argument, method.getName() + "." + argument.getIndex());
                        } else {
                            throw new IllegalArgumentException(
                                    "Argument config must set index or type attribute.eg: <dubbo:argument index='0' .../> or <dubbo:argument type=xxx .../>");
                        }

                    }
                }
            } // end of methods for
        }
        //泛化调用配置
        if (ProtocolUtils.isGeneric(generic)) {
            map.put(GENERIC_KEY, generic);
            map.put(METHODS_KEY, ANY_VALUE);
        } else {
            //保存version
            String revision = Version.getVersion(interfaceClass, version);
            if (revision != null && revision.length() > 0) {
                map.put(REVISION_KEY, revision);
            }

            String[] methods = Wrapper.getWrapper(interfaceClass).getMethodNames();
            if (methods.length == 0) {
                logger.warn("No method found in service interface " + interfaceClass.getName());
                map.put(METHODS_KEY, ANY_VALUE);
            } else {
                //方法名使用,拼接
                map.put(METHODS_KEY, StringUtils.join(new HashSet<String>(Arrays.asList(methods)), ","));
            }
        }

        /**
         * token：通过令牌验证在注册中心控制权限可以防止消费者绕过注册中心访问提供者
         * 服务端配置true
         * @see org.apache.dubbo.rpc.filter.TokenFilter
         */
        if (ConfigUtils.isEmpty(token) && provider != null) {
            token = provider.getToken();
        }

        if (!ConfigUtils.isEmpty(token)) {
            //default:true | default，如果是默认的配置，会使用UUID随机值作为最终的token,读取是通过attachments读取
            if (ConfigUtils.isDefault(token)) {
                map.put(TOKEN_KEY, UUID.randomUUID().toString());
            } else {
                map.put(TOKEN_KEY, token);
            }
        }
        //将所有参数保存在service元数据中的附件中
        //private final Map<String, Object> attachments = new ConcurrentHashMap<String, Object>();
        serviceMetadata.getAttachments().putAll(map);

        // 服务段host org.apache.dubbo.common.utils.NetUtils.getLocalHost()
        String host = findConfigedHosts(protocolConfig, registryURLs, map);
        //服务端ip ExtensionLoader.getExtensionLoader(Protocol.class).getExtension(name).getDefaultPort();
        Integer port = findConfigedPorts(protocolConfig, name, map, protocolConfigNum);
        URL url = new URL(name, host, port, getContextPath(protocolConfig).map(p -> p + "/" + path).orElse(path), map);

        /**
         * ConfiguratorFactory 自定义配置，可以通过spi机制进行封装,
         * @see  org.apache.dubbo.rpc.cluster.configurator.AbstractConfigurator.configure()
         */
        if (ExtensionLoader.getExtensionLoader(ConfiguratorFactory.class)
                .hasExtension(url.getProtocol())) {
            url = ExtensionLoader.getExtensionLoader(ConfiguratorFactory.class)
                    .getExtension(url.getProtocol()).getConfigurator(url).configure(url);
        }

        String scope = url.getParameter(SCOPE_KEY);
        // don't export when none is configured
        if (!SCOPE_NONE.equalsIgnoreCase(scope)) {

            /**
             * reference 如果没有设置scope，会默认在同时在本地和远程发布，如果指定scope，就会在发布指定环境 reference.setLocal("local"/"remote");
             * 本地发布地址:injvm://127.0.0.1/org.apache.dubbo.demo.DemoService?anyhost=true&application=dubbo-demo-api-provider
             * &bind.ip=192.168.0.102&bind.port=20880&default=true&deprecated=false&dubbo=2.0.2&dynamic=true&generic=false
             * &interface=org.apache.dubbo.demo.DemoService&methods=sayHiAsync,sayHello,sayHelloAsync,sayHi&pid=9652&release=
             * &sayHello.0.callback=false&sayHello.retries=3&service.name=ServiceBean:/org.apache.dubbo.demo.DemoService&side=provider
             * &timestamp=1675988008317
             */
            if (!SCOPE_REMOTE.equalsIgnoreCase(scope)) {
                exportLocal(url);
            }
            // 远程发布,只要scope不是local就会执行这一步
            if (!SCOPE_LOCAL.equalsIgnoreCase(scope)) {
                if (CollectionUtils.isNotEmpty(registryURLs)) {
                    for (URL registryURL : registryURLs) {
                        //service-discovery-registry 用来实现服务发现
                        //org.apache.dubbo.registry.client.ServiceDiscoveryRegistryFactory
                        if (SERVICE_REGISTRY_PROTOCOL.equals(registryURL.getProtocol())) {
                            url = url.addParameterIfAbsent(SERVICE_NAME_MAPPING_KEY, "true");
                        }

                        //jvm本地发布不需要注册
                        if (LOCAL_PROTOCOL.equalsIgnoreCase(url.getProtocol())) {
                            continue;
                        }
                        /**
                         * DYNAMIC_KEY:dynamic 表示是否要对数据在注册中心进行持久化,false:表示以静态形式注册 -> 持久化保存
                         * 即使执行unRegistry，该URL还是会保存在注册中心
                         * 如不需要需人工禁用。
                         * if (ephemeral) {
                         *     createEphemeral(path);
                         * } else {
                         *     createPersistent(path);
                         *     persistentExistNodePath.add(path);
                         * }
                         */
                        url = url.addParameterIfAbsent(DYNAMIC_KEY, registryURL.getParameter(DYNAMIC_KEY));
                        //需要配置monitor协议参数protocol
                        //org.apache.dubbo.config.MonitorConfig.protocol
                        //<dubbo:monitor address="xxx" />
                        URL monitorUrl = ConfigValidationUtils.loadMonitor(this, registryURL);
                        if (monitorUrl != null) {
                            url = url.addParameterAndEncoded(MONITOR_KEY, monitorUrl.toFullString());
                        }
                        if (logger.isInfoEnabled()) {
                            if (url.getParameter(REGISTER_KEY, true)) {
                                logger.info("Register dubbo service " + interfaceClass.getName() + " url " + url + " to registry " +
                                        registryURL);
                            } else {
                                logger.info("Export dubbo service " + interfaceClass.getName() + " to url " + url);
                            }
                        }

                        // For providers, this is used to enable custom proxy to generate invoker
                        //org.apache.dubbo.rpc.ProxyFactory 使用SPI扩展机制，默认使用javassist代理
                        //
                        String proxy = url.getParameter(PROXY_KEY);
                        if (StringUtils.isNotEmpty(proxy)) {
                            registryURL = registryURL.addParameter(PROXY_KEY, proxy);
                        }

                        //PROXY_FACTORY.getInvoker() 使用SPI机制获取代理类工厂,默认assist
                        //PROXY_FACTORY = ExtensionLoader.getExtensionLoader(ProxyFactory.class).getAdaptiveExtension();
                        //StubProxyFactoryWrapper -> JavassistProxyFactory
                        //addParameterAndEncoded() 对于url中参数值可能会包含有特殊字符(? & =)，所以通常会做一次url编码处理，避免冲突
                        //registry -> exporter
                        Invoker<?> invoker = PROXY_FACTORY.getInvoker(ref, (Class) interfaceClass,
                                registryURL.addParameterAndEncoded(EXPORT_KEY, url.toFullString()));
                        //将服务提供方invoker和元数据整合成一个封装类，该封装类实现Invoker类，通过export方法将Invoker一路透传下去
                        DelegateProviderMetaDataInvoker wrapperInvoker = new DelegateProviderMetaDataInvoker(invoker, this);

                        /**
                         * Protocol = ExtensionLoader.getExtensionLoader(Protocol.class).getAdaptiveExtension();
                         * 1、wrapper机制
                         * 2、spi机制
                         * ProtocolFilterWrapper -> ProtocolListenerWrapper -> InterfaceCompatibleRegistryProtocol =>
                         * doLocalExport ->
                         *    Invoker<?> invokerDelegate = new InvokerDelegate<>(originInvoker, providerUrl);
                         *    return new ExporterChangeableWrapper<>((Exporter<T>) protocol.export(invokerDelegate), originInvoker);
                         * ProtocolFilterWrapper -> ProtocolListenerWrapper -> DubboProtocol#export()
                         * 这时才真正进入Dubbo
                         * 1、第一步开启服务等待连接，底层实现为socket编程，创建socket连接，绑定端口号 dubbo默认使用Netty框架
                         *      openServer(url) -> createServer()
                         *      Exchangers.bind() 门面模式
                         *      Exchanger.bind() spi 默认HeaderExchanger
                         *      Transporters.bind() 门面模式
                         *      Transporter.bind() spi 默认NettyTransporter netty4
                         *      new NettyServer(url, handler);
                         *      doOpen();
                         *      初始化：bootstrap，workGroup，bossGroup，channels等Netty组件
                         *      如何监听客户端请求 等待连接？讲讲Netty原理
                         * 2、第二步注册url到注册中心
                         *      通过spi获取Registry
                         *      ListenerRegistryWrapper
                         *      -> FailbackRegistry
                         *          removeFailedRegistered(url);
                         *          removeFailedUnregistered(url);
                         *      -> ZookeeperRegistry.doRegistry();
                         *      -> AbstractZookeeperClient.create();
                         */
                        Exporter<?> exporter = PROTOCOL.export(wrapperInvoker);
                        exporters.add(exporter);
                    }
                } else {
                    if (logger.isInfoEnabled()) {
                        logger.info("Export dubbo service " + interfaceClass.getName() + " to url " + url);
                    }
                    Invoker<?> invoker = PROXY_FACTORY.getInvoker(ref, (Class) interfaceClass, url);
                    DelegateProviderMetaDataInvoker wrapperInvoker = new DelegateProviderMetaDataInvoker(invoker, this);

                    Exporter<?> exporter = PROTOCOL.export(wrapperInvoker);
                    exporters.add(exporter);
                }

                MetadataUtils.publishServiceDefinition(url);
            }
        }
        this.urls.add(url);
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    /**
     * always export injvm
     */
    private void exportLocal(URL url) {
        URL local = URLBuilder.from(url)
                .setProtocol(LOCAL_PROTOCOL)
                .setHost(LOCALHOST_VALUE)
                .setPort(0)
                .build();
        /**
         * ExtensionLoader.getExtensionLoader(Protocol.class).getAdaptiveExtension();
         * 通过SPI机制以及Dubbo特有的Wrapper封装机制对Protocol对象进行链式封装
         * 因为是在本地发布，使用的是injvm协议，最终实现类为 InjvmProtocol
         * 这里的 injvm协议最终调用顺序为 ProtocolFilterWrapper#export() -> ProtocolListenerWrapper#export() -> InjvmProtocol#export()
         * 本地export执行逻辑比较简单：exporterMap.addExportMap(serviceKey, tInjvmExporter);将其地址保存在本地缓存即可
         */
        Exporter<?> exporter = PROTOCOL.export(
                PROXY_FACTORY.getInvoker(ref, (Class) interfaceClass, local));
        exporters.add(exporter);
        logger.info("Export dubbo service " + interfaceClass.getName() + " to local registry url : " + local);
    }

    /**
     * Determine if it is injvm
     *
     * @return
     */
    private boolean isOnlyInJvm() {
        return getProtocols().size() == 1
                && LOCAL_PROTOCOL.equalsIgnoreCase(getProtocols().get(0).getName());
    }


    /**
     * Register & bind IP address for service provider, can be configured separately.
     * Configuration priority: environment variables -> java system properties -> host property in config file ->
     * /etc/hosts -> default network address -> first available network address
     *
     * @param protocolConfig
     * @param registryURLs
     * @param map
     * @return
     */
    private String findConfigedHosts(ProtocolConfig protocolConfig,
                                     List<URL> registryURLs,
                                     Map<String, String> map) {
        boolean anyhost = false;

        String hostToBind = getValueFromConfig(protocolConfig, DUBBO_IP_TO_BIND);
        if (hostToBind != null && hostToBind.length() > 0 && isInvalidLocalHost(hostToBind)) {
            throw new IllegalArgumentException("Specified invalid bind ip from property:" + DUBBO_IP_TO_BIND + ", value:" + hostToBind);
        }

        // if bind ip is not found in environment, keep looking up
        if (StringUtils.isEmpty(hostToBind)) {
            hostToBind = protocolConfig.getHost();
            if (provider != null && StringUtils.isEmpty(hostToBind)) {
                hostToBind = provider.getHost();
            }
            if (isInvalidLocalHost(hostToBind)) {
                anyhost = true;
                logger.info("No valid ip found from environment, try to get local host.");
                hostToBind = getLocalHost();
            }
        }

        map.put(BIND_IP_KEY, hostToBind);

        // registry ip is not used for bind ip by default
        String hostToRegistry = getValueFromConfig(protocolConfig, DUBBO_IP_TO_REGISTRY);
        if (hostToRegistry != null && hostToRegistry.length() > 0 && isInvalidLocalHost(hostToRegistry)) {
            throw new IllegalArgumentException(
                    "Specified invalid registry ip from property:" + DUBBO_IP_TO_REGISTRY + ", value:" + hostToRegistry);
        } else if (StringUtils.isEmpty(hostToRegistry)) {
            // bind ip is used as registry ip by default
            hostToRegistry = hostToBind;
        }

        map.put(ANYHOST_KEY, String.valueOf(anyhost));

        return hostToRegistry;
    }


    /**
     * Register port and bind port for the provider, can be configured separately
     * Configuration priority: environment variable -> java system properties -> port property in protocol config file
     * -> protocol default port
     *
     * @param protocolConfig
     * @param name
     * @param protocolConfigNum
     * @return
     */
    private Integer findConfigedPorts(ProtocolConfig protocolConfig,
                                      String name,
                                      Map<String, String> map, int protocolConfigNum) {
        Integer portToBind = null;

        // parse bind port from environment
        String port = getValueFromConfig(protocolConfig, DUBBO_PORT_TO_BIND);
        portToBind = parsePort(port);

        // if there's no bind port found from environment, keep looking up.
        if (portToBind == null) {
            portToBind = protocolConfig.getPort();
            if (provider != null && (portToBind == null || portToBind == 0)) {
                portToBind = provider.getPort();
            }
            final int defaultPort = ExtensionLoader.getExtensionLoader(Protocol.class).getExtension(name).getDefaultPort();
            if (portToBind == null || portToBind == 0) {
                portToBind = defaultPort;
            }
            if (portToBind <= 0) {
                portToBind = getRandomPort(name);
                if (portToBind == null || portToBind < 0) {
                    portToBind = getAvailablePort(defaultPort);
                    putRandomPort(name, portToBind);
                }
            }
        }

        // registry port, not used as bind port by default
        String key = DUBBO_PORT_TO_REGISTRY;
        if (protocolConfigNum > 1) {
            key = getProtocolConfigId(protocolConfig).toUpperCase() + "_" + key;
        }
        String portToRegistryStr = getValueFromConfig(protocolConfig, key);
        Integer portToRegistry = parsePort(portToRegistryStr);
        if (portToRegistry != null) {
            portToBind = portToRegistry;
        }

        // save bind port, used as url's key later
        map.put(BIND_PORT_KEY, String.valueOf(portToBind));

        return portToBind;
    }


    private String getProtocolConfigId(ProtocolConfig config) {
        return Optional.ofNullable(config.getId()).orElse(DUBBO);
    }

    private Integer parsePort(String configPort) {
        Integer port = null;
        if (configPort != null && configPort.length() > 0) {
            try {
                Integer intPort = Integer.parseInt(configPort);
                if (isInvalidPort(intPort)) {
                    throw new IllegalArgumentException("Specified invalid port from env value:" + configPort);
                }
                port = intPort;
            } catch (Exception e) {
                throw new IllegalArgumentException("Specified invalid port from env value:" + configPort);
            }
        }
        return port;
    }

    private String getValueFromConfig(ProtocolConfig protocolConfig, String key) {
        String protocolPrefix = protocolConfig.getName().toUpperCase() + "_";
        String value = ConfigUtils.getSystemProperty(protocolPrefix + key);
        if (StringUtils.isEmpty(value)) {
            value = ConfigUtils.getSystemProperty(key);
        }
        return value;
    }

    private Integer getRandomPort(String protocol) {
        protocol = protocol.toLowerCase();
        return RANDOM_PORT_MAP.getOrDefault(protocol, Integer.MIN_VALUE);
    }

    private void putRandomPort(String protocol, Integer port) {
        protocol = protocol.toLowerCase();
        if (!RANDOM_PORT_MAP.containsKey(protocol)) {
            RANDOM_PORT_MAP.put(protocol, port);
            logger.warn("Use random available port(" + port + ") for protocol " + protocol);
        }
    }

    private void postProcessConfig() {
        List<ConfigPostProcessor> configPostProcessors = ExtensionLoader.getExtensionLoader(ConfigPostProcessor.class)
                .getActivateExtension(URL.valueOf(CONFIG_POST_PROCESSOR_PROTOCOL), (String[]) null);
        configPostProcessors.forEach(component -> component.postProcessServiceConfig(this));
    }

    /**
     * Dispatch an {@link Event event}
     *
     * @param event an {@link Event event}
     * @since 2.7.5
     */
    private void dispatch(Event event) {
        EventDispatcher.getDefaultExtension().dispatch(event);
    }

    public DubboBootstrap getBootstrap() {
        return bootstrap;
    }

    public void setBootstrap(DubboBootstrap bootstrap) {
        this.bootstrap = bootstrap;
    }

    public String getServiceName() {

        if (!StringUtils.isBlank(serviceName)) {
            return serviceName;
        }
        String generateVersion = version;
        String generateGroup = group;

        if (StringUtils.isBlank(version) && provider != null) {
            generateVersion = provider.getVersion();
        }

        if (StringUtils.isBlank(group) && provider != null) {
            generateGroup = provider.getGroup();
        }
        StringBuilder stringBuilder = new StringBuilder();
        stringBuilder.append("ServiceBean:");

        if (!StringUtils.isBlank(generateGroup)) {
            stringBuilder.append(generateGroup);
        }

        stringBuilder.append("/").append(interfaceName);

        if (!StringUtils.isBlank(generateVersion)) {
            stringBuilder.append(":").append(generateVersion);
        }

        serviceName = stringBuilder.toString();
        return serviceName;
    }
}
