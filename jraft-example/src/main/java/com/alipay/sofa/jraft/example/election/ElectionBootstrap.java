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
package com.alipay.sofa.jraft.example.election;

import com.alipay.sofa.jraft.entity.PeerId;
import com.alipay.sofa.jraft.option.NodeOptions;
import com.alipay.sofa.jraft.rhea.metrics.KVMetrics;
import com.codahale.metrics.ConsoleReporter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.TimeUnit;

/**
 *
 * @author jiachun.fjc
 */
public class ElectionBootstrap {

    private static final Logger log = LoggerFactory.getLogger(ElectionBootstrap.class);

    // Start elections by 3 instance. Note that if multiple instances are started on the same machine,
    // the first parameter `dataPath` should not be the same.
    public static void main(final String[] args) {
        String dataPath = "D:/jraft/node808";
        String groupId = "my_group";
        int nodeCount = 3;
        String serverIdStr = args[0];
        String corServerAddr = null;
        String initialConfStr = "";
        for (int i = 1; i <= nodeCount; i++) {
            String serverAddr = "127.0.0.1:808" + i;
            initialConfStr += serverAddr + ",";
            if (Integer.valueOf(serverIdStr) == Integer.valueOf(i)) {
                corServerAddr = serverAddr;
                dataPath = dataPath + i;
            }
        }

        initialConfStr = initialConfStr.substring(0, initialConfStr.length() - 1);

        log.info("dataPath : {}", dataPath);
        log.info("corServerAddr : {}", corServerAddr);
        log.info("initialConfStr : {}", initialConfStr);


        final ElectionNodeOptions electionOpts = new ElectionNodeOptions();
        electionOpts.setDataPath(dataPath);
        electionOpts.setGroupId(groupId);
        electionOpts.setServerAddress(corServerAddr);
        electionOpts.setInitialServerAddressList(initialConfStr);

        //开启metrics监控
        NodeOptions nodeOptions = new NodeOptions();
        nodeOptions.setEnableMetrics(true);
        nodeOptions.setElectionTimeoutMs(20000);
        nodeOptions.getRaftOptions().setElectionHeartbeatFactor(2);
        electionOpts.setNodeOptions(nodeOptions);

        final ElectionNode node = new ElectionNode();
        node.addLeaderStateListener(new LeaderStateListener() {

            @Override
            public void onLeaderStart(long leaderTerm) {
                PeerId serverId = node.getNode().getLeaderId();
                String ip       = serverId.getIp();
                int    port     = serverId.getPort();
                System.out.println("[ElectionBootstrap] Leader's ip is: " + ip + ", port: " + port);
                System.out.println("[ElectionBootstrap] Leader start on term: " + leaderTerm);
            }

            @Override
            public void onLeaderStop(long leaderTerm) {
                System.out.println("[ElectionBootstrap] Leader stop on term: " + leaderTerm);
            }
        });
        node.init(electionOpts);

//        if (node.getNode().getNodeMetrics().getMetricRegistry() == null) {
//            log.warn("MetricRegistry is null");
//        } else {
//            ConsoleReporter.forRegistry(node.getNode().getNodeMetrics().getMetricRegistry()) //
//                    .build() //
//                    .start(30, TimeUnit.SECONDS);
//        }



    }
}
