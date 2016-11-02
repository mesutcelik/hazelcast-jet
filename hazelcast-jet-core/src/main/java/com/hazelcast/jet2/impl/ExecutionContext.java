/*
 * Copyright (c) 2008-2016, Hazelcast, Inc. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.hazelcast.jet2.impl;

import com.hazelcast.core.Member;
import com.hazelcast.internal.util.concurrent.ConcurrentConveyor;
import com.hazelcast.internal.util.concurrent.OneToOneConcurrentArrayQueue;
import com.hazelcast.internal.util.concurrent.QueuedPipe;
import com.hazelcast.jet2.DAG;
import com.hazelcast.jet2.Edge;
import com.hazelcast.jet2.JetEngineConfig;
import com.hazelcast.jet2.Processor;
import com.hazelcast.jet2.ProcessorMetaSupplier;
import com.hazelcast.jet2.ProcessorMetaSupplierContext;
import com.hazelcast.jet2.ProcessorSupplier;
import com.hazelcast.jet2.ProcessorSupplierContext;
import com.hazelcast.jet2.Vertex;
import com.hazelcast.jet2.impl.deployment.DeploymentStore;
import com.hazelcast.jet2.impl.deployment.JetClassLoader;
import com.hazelcast.nio.Address;
import com.hazelcast.spi.NodeEngine;

import java.security.AccessController;
import java.security.PrivilegedAction;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static com.hazelcast.internal.util.concurrent.ConcurrentConveyor.concurrentConveyor;
import static com.hazelcast.jet2.impl.DoneItem.DONE_ITEM;
import static java.util.stream.Collectors.toList;
import static java.util.stream.Collectors.toMap;

public class ExecutionContext {

    public static final int QUEUE_SIZE = 1024;

    private final String name;
    private NodeEngine nodeEngine;
    private ExecutionService executionService;
    private DeploymentStore deploymentStore;
    private JetEngineConfig config;
    private JetClassLoader classLoader;
    private AtomicInteger idCounter = new AtomicInteger();

    public ExecutionContext(String name, NodeEngine nodeEngine, JetEngineConfig config) {
        this.name = name;
        this.nodeEngine = nodeEngine;
        this.executionService = new ExecutionService(nodeEngine.getHazelcastInstance(), name, config);
        this.deploymentStore = new DeploymentStore(config.getDeploymentDirectory());

        this.config = config;
        AccessController.doPrivileged((PrivilegedAction<Void>) () -> {
            this.classLoader = new JetClassLoader(deploymentStore);
            return null;
        });
    }

    public Map<Member, ExecutionPlan> buildExecutionPlan(DAG dag) {
        List<Member> members = new ArrayList<>(nodeEngine.getClusterService().getMembers());
        int clusterSize = members.size();
        final int planId = idCounter.getAndIncrement();
        Map<Member, ExecutionPlan> plans = members.stream().collect(toMap(m -> m, m -> new ExecutionPlan(planId)));
        Map<Vertex, Integer> vertexIdMap = assignVertexIds(dag);
        for (Map.Entry<Vertex, Integer> entry : vertexIdMap.entrySet()) {
            Vertex vertex = entry.getKey();
            int vertexId = entry.getValue();
            int perNodeParallelism = getParallelism(vertex);
            int totalParallelism = perNodeParallelism * clusterSize;

            List<Edge> outboundEdges = dag.getOutboundEdges(vertex);
            List<Edge> inboundEdges = dag.getInboundEdges(vertex);

            ProcessorMetaSupplier supplier = vertex.getSupplier();
            supplier.init(ProcessorMetaSupplierContext.of(
                    nodeEngine.getHazelcastInstance(), totalParallelism, perNodeParallelism));
            List<EdgeDef> outputs = outboundEdges.stream().map(edge -> {
                int otherEndId = vertexIdMap.get(edge.getDestination());
                return new EdgeDef(otherEndId, edge.getOutputOrdinal(), edge.getInputOrdinal(),
                        edge.getPriority(), edge.getForwardingPattern(), edge.getPartitioner());
            }).collect(toList());

            List<EdgeDef> inputs = inboundEdges.stream().map(edge -> {
                int otherEndId = vertexIdMap.get(edge.getSource());
                return new EdgeDef(otherEndId, edge.getInputOrdinal(), edge.getInputOrdinal(),
                        edge.getPriority(), edge.getForwardingPattern(), edge.getPartitioner());
            }).collect(toList());

            for (Entry<Member, ExecutionPlan> e : plans.entrySet()) {
                ProcessorSupplier processorSupplier = supplier.get(e.getKey().getAddress());
                VertexDef vertexDef = new VertexDef(vertexId, processorSupplier, perNodeParallelism);
                vertexDef.addOutputs(outputs);
                vertexDef.addInputs(inputs);
                e.getValue().addVertex(vertexDef);
            }
        }

        return plans;
    }

    public Future<Void> executePlan(ExecutionPlan plan) {
        List<Tasklet> tasks = new ArrayList<>();
        Map<String, ConcurrentConveyor<Object>[]> conveyorMap = new HashMap<>();
        Map<Integer, VertexDef> vMap = plan.getVertices().stream().collect(toMap(VertexDef::getId, v -> v));
        int partitionCount = nodeEngine.getPartitionService().getPartitionCount();

        for (VertexDef vertexDef : plan.getVertices()) {
            List<EdgeDef> inputs = vertexDef.getInputs();
            List<EdgeDef> outputs = vertexDef.getOutputs();
            ProcessorSupplier processorSupplier = vertexDef.getProcessorSupplier();
            int parallelism = vertexDef.getParallelism();
            processorSupplier.init(ProcessorSupplierContext.of(nodeEngine.getHazelcastInstance(), parallelism));
            List<Processor> processors = processorSupplier.get(parallelism);

            // allocate partitions to tasks
            // TODO: should be done by processor supplier
            Map<Integer, List<Integer>> partitionGrouping = IntStream
                    .range(0, partitionCount)
                    .boxed()
                    .collect(Collectors.groupingBy(m -> m % parallelism));

            for (int i = 0; i < parallelism; i++) {
                List<InboundEdgeStream> inboundStreams = new ArrayList<>();
                List<OutboundEdgeStream> outboundStreams = new ArrayList<>();
                final int taskletIndex = i; // final copy of i, as needed in lambdas below
                for (EdgeDef output : outputs) {

                    int destinationId = output.getOtherEndId();
                    int numLocalConsumers = vMap.get(destinationId).getParallelism();

                    // distribute local partitions among the local consumers
                    Map<Integer, List<Integer>> localPartitions = nodeEngine.getPartitionService()
                            .getMemberPartitions(nodeEngine.getThisAddress())
                            .stream().collect(Collectors.groupingBy(p -> p % numLocalConsumers));

                    // distribute remote partitions
                    Map<Address, List<Integer>> remotePartitions = nodeEngine.getPartitionService()
                            .getMemberPartitionsMap()
                            .entrySet().stream()
                            .filter(e -> !e.getKey().equals(nodeEngine.getThisAddress()))
                            .collect(Collectors.toMap(Entry::getKey, Entry::getValue));

                    // each edge has an array of conveyors
                    // one conveyor per consumer - each conveyor has one queue per producer
                    // giving a total of number of producers * number of consumers queues
                    String id = vertexDef.getId() + ":" + output.getOtherEndId();
                    final ConcurrentConveyor<Object>[] localConveyorArray = conveyorMap.computeIfAbsent(id,
                            e -> createConveyorArray(numLocalConsumers, parallelism, QUEUE_SIZE));
                    OutboundCollector[] localCollectors = new OutboundCollector[localConveyorArray.length];
                    Arrays.setAll(localCollectors, n -> new ConveyorCollector(localConveyorArray[n], taskletIndex,
                            localPartitions.get(n)));

                    OutboundCollector[] allCollectors = new OutboundCollector[remotePartitions.size() + 1];
                    int index = 0;
                    allCollectors[index++] = OutboundCollector.compositeCollector(localCollectors, output, partitionCount);
                    for (Entry<Address, List<Integer>> entry : remotePartitions.entrySet()) {

                        allCollectors[index++] = new RemoteOutboundCollector(nodeEngine, name, entry.getKey(),
                                plan.getId(), destinationId, output.getOtherEndOrdinal(), entry.getValue());
                    }

                    OutboundCollector collector = OutboundCollector.compositeCollector(allCollectors,
                            output, partitionCount);
                    outboundStreams.add(new OutboundEdgeStream(output.getOrdinal(), collector));
                }

                for (EdgeDef input : inputs) {
                    // each tasklet will have one input conveyor per edge
                    // and one InboundEmitter per queue on the conveyor
                    String id = input.getOtherEndId() + ":" + vertexDef.getId();
                    final ConcurrentConveyor<Object> conveyor = conveyorMap.get(id)[taskletIndex];
                    InboundEmitter[] emitters = new InboundEmitter[conveyor.queueCount()];
                    Arrays.setAll(emitters, n -> new ConveyorEmitter(conveyor, n, partitionGrouping.get(taskletIndex)));
                    ConcurrentInboundEdgeStream inboundStream = new ConcurrentInboundEdgeStream(
                            emitters, input.getOrdinal(), input.getPriority());
                    inboundStreams.add(inboundStream);
                }
                tasks.add(new ProcessorTasklet(processors.get(i), classLoader, inboundStreams, outboundStreams));
            }
        }

        return executionService.execute(tasks);
    }

    private int getParallelism(Vertex vertex) {
        return vertex.getParallelism() != -1 ? vertex.getParallelism() : config.getParallelism();
    }

    private static Map<Vertex, Integer> assignVertexIds(DAG dag) {
        int vertexId = 0;
        Map<Vertex, Integer> vertexIdMap = new LinkedHashMap<>();
        for (Iterator<Vertex> iterator = dag.iterator(); iterator.hasNext(); vertexId++) {
            vertexIdMap.put(iterator.next(), vertexId);
        }
        return vertexIdMap;
    }

    @SuppressWarnings("unchecked")
    private static ConcurrentConveyor<Object>[] createConveyorArray(int count, int queueCount, int queueSize) {
        ConcurrentConveyor<Object>[] concurrentConveyors = new ConcurrentConveyor[count];
        Arrays.setAll(concurrentConveyors, i -> {
            QueuedPipe<Object>[] queues = new QueuedPipe[queueCount];
            Arrays.setAll(queues, j -> new OneToOneConcurrentArrayQueue<>(queueSize));
            return concurrentConveyor(DONE_ITEM, queues);
        });
        return concurrentConveyors;
    }

    public DeploymentStore getDeploymentStore() {
        return deploymentStore;
    }

    public JetEngineConfig getConfig() {
        return config;
    }

    public void handleIncoming(Payload payload) {
        System.out.println("Received payload = " + payload);
        // put item into correct queue
    }

    public void destroy() {
        executionService.shutdown();
    }


}

