/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements. See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership. The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at
 * 
 * http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied. See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.stratos.messaging.message.processor.topology;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.stratos.messaging.domain.topology.Cluster;
import org.apache.stratos.messaging.domain.topology.Member;
import org.apache.stratos.messaging.domain.topology.Service;
import org.apache.stratos.messaging.domain.topology.Topology;
import org.apache.stratos.messaging.event.topology.MemberTerminatedEvent;
import org.apache.stratos.messaging.message.filter.topology.TopologyClusterFilter;
import org.apache.stratos.messaging.message.filter.topology.TopologyMemberFilter;
import org.apache.stratos.messaging.message.filter.topology.TopologyServiceFilter;
import org.apache.stratos.messaging.message.processor.MessageProcessor;
import org.apache.stratos.messaging.util.Util;

public class MemberTerminatedMessageProcessor extends MessageProcessor {

    private static final Log log = LogFactory.getLog(MemberTerminatedMessageProcessor.class);
    private MessageProcessor nextProcessor;

    @Override
    public void setNext(MessageProcessor nextProcessor) {
        this.nextProcessor = nextProcessor;
    }

    @Override
    public boolean process(String type, String message, Object object) {
        Topology topology = (Topology) object;

        if (MemberTerminatedEvent.class.getName().equals(type)) {
            // Return if topology has not been initialized
            if (!topology.isInitialized())
                return false;

            // Parse complete message and build event
            MemberTerminatedEvent event = (MemberTerminatedEvent) Util.jsonToObject(message, MemberTerminatedEvent.class);

            // Apply service filter
            if (TopologyServiceFilter.getInstance().isActive()) {
                if (TopologyServiceFilter.getInstance().serviceNameExcluded(event.getServiceName())) {
                    // Service is excluded, do not update topology or fire event
                    if (log.isDebugEnabled()) {
                        log.debug(String.format("Service is excluded: [service] %s", event.getServiceName()));
                    }
                    return false;
                }
            }

            // Apply cluster filter
            if (TopologyClusterFilter.getInstance().isActive()) {
                if (TopologyClusterFilter.getInstance().clusterIdExcluded(event.getClusterId())) {
                    // Cluster is excluded, do not update topology or fire event
                    if (log.isDebugEnabled()) {
                        log.debug(String.format("Cluster is excluded: [cluster] %s", event.getClusterId()));
                    }
                    return false;
                }
            }

            // Validate event against the existing topology
            Service service = topology.getService(event.getServiceName());
            if (service == null) {
                if (log.isWarnEnabled()) {
                    log.warn(String.format("Service does not exist: [service] %s", event.getServiceName()));
                }
                return false;
            }
            Cluster cluster = service.getCluster(event.getClusterId());
            if (cluster == null) {
                if (log.isWarnEnabled()) {
                    log.warn(String.format("Cluster does not exist: [service] %s [cluster] %s",
                            event.getServiceName(), event.getClusterId()));
                }
                return false;
            }
            Member member = cluster.getMember(event.getMemberId());
            if(member != null) {
                // Apply member filter
                if(TopologyMemberFilter.getInstance().isActive()) {
                    if(TopologyMemberFilter.getInstance().lbClusterIdExcluded(member.getLbClusterId())) {
                        if (log.isDebugEnabled()) {
                            log.debug(String.format("Member is excluded: [lb-cluster-id] %s", member.getLbClusterId()));
                        }
                        return false;
                    }
                }
            }

            // Notify event listeners before removing member object
            notifyEventListeners(event);

            if (member == null) {
                if (log.isWarnEnabled()) {
                    log.warn(String.format("Member already terminated: [service] %s [cluster] %s [member] %s",
                            event.getServiceName(),
                            event.getClusterId(),
                            event.getMemberId()));
                }
            } else {
            	// Remove member from the cluster
            	cluster.removeMember(member);
            	
            	if (log.isInfoEnabled()) {
            		log.info(String.format("Member terminated: [service] %s [cluster] %s [member] %s",
            				event.getServiceName(),
            				event.getClusterId(),
            				event.getMemberId()));
            	}
            }

            return true;
        } else {
            if (nextProcessor != null) {
                // ask the next processor to take care of the message.
                return nextProcessor.process(type, message, topology);
            } else {
                throw new RuntimeException(String.format("Failed to process message using available message processors: [type] %s [body] %s", type, message));
            }
        }
    }
}
