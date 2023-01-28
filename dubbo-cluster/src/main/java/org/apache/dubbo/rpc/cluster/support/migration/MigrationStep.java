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
package org.apache.dubbo.rpc.cluster.support.migration;

public enum MigrationStep {
    /**
     * 在 Dubbo 3 之前地址注册模型是以接口级粒度注册到注册中心的，而 Dubbo 3 全新的应用级注册模型注册到注册中心的粒度是应用级的。
     * 从注册中心的实现上来说是几乎不一样的，这导致了对于从接口级注册模型获取到的 invokers 是无法与从应用级注册模型获取到的 invokers 进行合并的。
     * 为了帮助用户从接口级往应用级迁移，Dubbo 3 设计了 Migration 机制，基于三个状态的切换实现实际调用中地址模型的切换。
     */
    FORCE_INTERFACE,
    APPLICATION_FIRST,
    FORCE_APPLICATION
}