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
package org.apache.dubbo.demo.consumer;

import org.apache.dubbo.config.ApplicationConfig;
import org.apache.dubbo.config.ReferenceConfig;
import org.apache.dubbo.config.RegistryConfig;
import org.apache.dubbo.config.bootstrap.DubboBootstrap;
import org.apache.dubbo.config.utils.ReferenceConfigCache;
import org.apache.dubbo.demo.DemoService;
import org.apache.dubbo.rpc.service.GenericService;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;

public class Application {
    public static void main(String[] args) throws ExecutionException, InterruptedException {
        runWithRefer();
//        runWithBootstrap();
    }

    private static boolean isClassic(String[] args) {
        return args.length > 0 && "classic".equalsIgnoreCase(args[0]);
    }

    private static void runWithBootstrap() {
        ReferenceConfig<DemoService> reference = new ReferenceConfig<>();
        reference.setInterface(DemoService.class);
        reference.setGeneric("true");

        DubboBootstrap bootstrap = DubboBootstrap.getInstance();
        bootstrap.application(new ApplicationConfig("dubbo-demo-api-consumer"))
                .registry(new RegistryConfig("zookeeper://127.0.0.1:2181;zookeeper://127.0.0.1:2182"))
//                .registry(new RegistryConfig("nacos://127.0.0.1:8848"))
                .reference(reference)
                .start();

        DemoService demoService = ReferenceConfigCache.getCache().get(reference);
        String message = demoService.sayHello("dubbo");
        System.out.println(message);

        // generic invoke
        GenericService genericService = (GenericService) demoService;
        Object genericInvokeResult = genericService.$invoke("sayHello", new String[]{String.class.getName()},
                new Object[]{"dubbo generic invoke"});
        System.out.println(genericInvokeResult);
    }


    /**
     * 客户端：
     * 通过consumer代理、路由、负载、过滤器之后到达最终的DubboInvoker.doInvoke()方法，发起请求客户端currentClient.request(inv, timeout, executor).thenApply(obj -> (AppResponse) obj);
     * ReferenceCountExchangeClient.request() -> HeaderExchangeClient.request() -> HeaderExchangeChannel.sent() -> AbstractPeer.sent() -> AbstractClient.sent() -> NettyChannel.sent()
     * AbstractChannel -> writeAndFlush() -> 服务端触发读事件
     * <p>
     * 服务端：receiver -> handleRequest - > reply -> invoker.invoke() -> **Filter.invoke() -> AbstractProxyInvoker.invoke()
     * -> doInvoke() -> 动态类doInvoke() -> method.invoke() -> DemoServiceImpl.sayHello()
     * -> AbstractProxyInvoker.wrapWithFuture() -> 一路返回到DubboProtocol:return result.thenApply(Function.identity());
     * -> HeaderExchangeHandler.handleRequest() 方法中channel.send(res); -> 判断是否关闭 -> nettyChannel.writeAndFlush()
     * -> 触发客户端eventLoop读事件 -> 解码 ->读取结果 -> FutureFilter ->AsyncToSyncInvoker -> 获取result，同步or异步
     * -> 根据invocationMode判断，异步直接返回，同步调用future的get方法阻塞获取直到超时
     * -> 一路返回result
     * <p>
     * if (executor != null && executor instanceof ThreadlessExecutor) {
     * ThreadlessExecutor threadlessExecutor = (ThreadlessExecutor) executor;
     * threadlessExecutor.waitAndDrain();
     * }
     * return responseFuture.get(timeout, unit);
     * <p>
     * 服务端：
     * eventLoop读事件：
     * NettyCodecAdapter$InternalDecoder.decode() -> DubboCountCodec.decode() 创建MultiMessage对象，decode后封装搭配该对象中，更新readIndex后返回result ->
     * ExchangeCodec.decode() 读取header ->  ExchangeCodec.decode() 检查魔术、检查长度、获取data length 、处理response长度超payload默认大小8M、检查payload、检查readable、
     * 转换成ChannelBufferInputStream 、DubboCodec.decodeBody() -> 请求 or 相应 -> 返回DecodeableRpcInvocation -> ExchangeCodec.decode() -> fireChannelRead() ->
     * DecodeableRpcInvocation.decode() -> HeaderExchangeHandler.received() -> handleRequest() -> HeaderExchangeChannel.sent() -> NettyChannel.sent(msg,sent)
     * msg:Response [id=0, version=2.0.2, status=20, event=false, error=null, result=AppResponse [value=Hello dubbo, response from provider: 192.168.111.1:20880, exception=null]]
     * ->判断是否关闭，关闭抛异常 -> writeAndFlush ->
     * Async线程：io.netty.channel.AbstractChannelHandlerContext.WriteTask- > AbstractChannelHandlerContext.invokeWriteAndFlush() -> NettyServerHandler.write() ->
     * InternalEncoder.encode() -> DubboCountCodec.encode() -> ExchangeCodec.encode() -> encodeResponse() -> AbstractChannelHandlerContext.invokeWrite0() -> NettyServerHandler.sent()
     * -> 触发客户端read事件
     * <p>
     * <p>
     * 客户端：
     * NioEventLoop -> selectKey -> read事件 -> read -> nettyChannel ->read -> fireChannelRead() ->
     * ChannelInboundHandler().channelRead -> ByteToMessageDecoder.callDecode() -> InternalDecoder().decode() -> DubboCountCodec.decode() ->ExchangeCodec.decode()
     * DubboCodec.decodeBody() -> fireChannelRead() -> invokeChannelRead() -> 封装消息到Result中 -> responseFuture.get();
     */
    private static void runWithRefer() throws ExecutionException, InterruptedException {
        ReferenceConfig<DemoService> reference = new ReferenceConfig<>();
        reference.setCheck(false);
//        reference.setConnections(5);
        reference.setApplication(new ApplicationConfig("dubbo-demo-api-consumer"));
        reference.setRegistry(new RegistryConfig("zookeeper://127.0.0.1:2181"));
//        reference.setRegistry(new RegistryConfig("N/A"));
        reference.setInterface(DemoService.class);
        reference.setUrl("dubbo://192.168.0.104:20880");
        reference.setProxy("jdk");
        reference.setTimeout(1000000);
//        reference.setLazy(true);
        //getProxy() -> RegistryProtocol -> DubboProtocol() 生成invoker -> 注册，订阅 ->
        // 为invoker生成代理 -> invocationHandler -> MigrationInvoker -> MockClusterInvoker -> AbstractCluster$InterceptorInvokerNode()
        //ClusterInterceptor.invoke() -> list  -> routeChains ->for(::) -> route ->FailoverClusterInvoker -> lb.select()->
        //InvokerWrapper ->  filterNode -> 链式执行
        //ConsumerContextFilter -> FutureFilter -> MonitorFilter -> ListenerInvokerWrapper -> AsyncToSyncInvoker -> AbstractInvoker.doInvoke
        //dubboInvoker -> 返回result
        // cluster -> 路由 -> 过滤器  -> 负载 -> 代理类() - > wrapWithFuture() -> 返回future -> AsyncRpcResult -> recreate -> get结果
        DemoService service = reference.get();
        String message = service.sayHello("dubbo");
//        reference.setStub(true);
//        reference.setStub("org.apache.dubbo.demo.DemoServiceStub");
//        CompletableFuture<String> completableFuture = service.sayHiAsync("dubbo");
//        boolean done = completableFuture.isDone();
//        System.out.println(done);
//        String message1 = completableFuture.get();
        System.out.println(message);
//        System.out.println(message1);

//        CompletableFuture<String> mandone = service.sayHelloAsync("mandone");
    }
}
