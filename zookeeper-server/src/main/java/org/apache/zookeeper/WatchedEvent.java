/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.zookeeper;

import org.apache.yetus.audience.InterfaceAudience;
import org.apache.zookeeper.proto.WatcherEvent;
import org.apache.zookeeper.Watcher.Event.EventType;
import org.apache.zookeeper.Watcher.Event.KeeperState;

/**
 *  A WatchedEvent represents a change on the ZooKeeper that a Watcher
 *  is able to respond to.  The WatchedEvent includes exactly what happened,
 *  the current state of the ZooKeeper, and the path of the znode that
 *  was involved in the event.
 *  对服务端事件的封装，是一个逻辑对象 WathcerEvent是网络传输的序列化对象，它俩本质是相同的
 */
@InterfaceAudience.Public
public class WatchedEvent {
    //逻辑状态
    final private KeeperState keeperState;
    //事件类型
    final private EventType eventType;
    //节点路径
    private String path;
    
    /**
     * Create a WatchedEvent with specified type, state and path
     */
    public WatchedEvent(EventType eventType, KeeperState keeperState, String path) {
        this.keeperState = keeperState;
        this.eventType = eventType;
        this.path = path;
    }
    
    /**
     * Convert a WatcherEvent sent over the wire into a full-fledged WatcherEvent
     */
    public WatchedEvent(WatcherEvent eventMessage) {
        keeperState = KeeperState.fromInt(eventMessage.getState());
        eventType = EventType.fromInt(eventMessage.getType());
        path = eventMessage.getPath();
    }
    
    public KeeperState getState() {
        return keeperState;
    }
    
    public EventType getType() {
        return eventType;
    }
    
    public String getPath() {
        return path;
    }

    @Override
    public String toString() {
        return "WatchedEvent state:" + keeperState
            + " type:" + eventType + " path:" + path;
    }

    /**
     *  Convert WatchedEvent to type that can be sent over network
     *  包装后用于网络传输
     */
    public WatcherEvent getWrapper() {
        return new WatcherEvent(eventType.getIntValue(), 
                                keeperState.getIntValue(), 
                                path);
    }
}
